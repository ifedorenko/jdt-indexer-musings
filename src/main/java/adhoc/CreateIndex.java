package adhoc;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javassist.bytecode.ClassFile;
import javassist.bytecode.ConstPool;

public class CreateIndex {
	private static final String ROCKSDBDIR = "/tmp/rocksdb-index";

	public static void main(String[] args) throws Exception {
		if (args.length != 1 || !Files.isDirectory(Paths.get(args[0]))) {
			System.out.printf("Usages: %s basedir\n", CreateIndex.class.getName());
			System.exit(-1);
		}

		Path basedir = Paths.get(args[0]).toRealPath();
		System.out.printf("rocksdb=%s; basedir=%s\n", ROCKSDBDIR, basedir.toString());

		Set<Path> files = new HashSet<>();
		Files.walkFileTree(basedir, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (attrs.isRegularFile() && file.toString().endsWith(".jar")) {
					files.add(file);
				}
				return FileVisitResult.CONTINUE;
			}
		});

		Files.walkFileTree(Paths.get(ROCKSDBDIR), new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}
		});

		RocksDB.loadLibrary();
		final ColumnFamilyOptions cfOpts = new ColumnFamilyOptions() //
				.optimizeLevelStyleCompaction() //
				.setCompressionType(CompressionType.ZSTD_COMPRESSION) //
				.setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION);
		final List<ColumnFamilyDescriptor> cfDescriptors = Arrays.asList(
				new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts),
				new ColumnFamilyDescriptor("parents".getBytes(), cfOpts),
				new ColumnFamilyDescriptor("methodrefs".getBytes(), cfOpts));
		columnFamilyHandleList = new ArrayList<>();
		final DBOptions options = new DBOptions() //
				.setCreateIfMissing(true) //
				.setCreateMissingColumnFamilies(true);
		db = RocksDB.open(options, ROCKSDBDIR, cfDescriptors, columnFamilyHandleList);

		writeOptions = new WriteOptions();
		writeOptions.setDisableWAL(true);
		writeOptions.setSync(false);

		long start = System.currentTimeMillis();

		for (Path file : files) {
			System.out.println(file);
			try (JarFile jar = new JarFile(file.toFile())) {
				indexJar(file, jar);
			}
		}

		for (final ColumnFamilyHandle columnFamilyHandle : columnFamilyHandleList) {
			columnFamilyHandle.close();
		}

		db.compactRange();
		db.close();
		options.close();
		cfOpts.close();

		System.out.printf("jarCount=%d; classCount=%d; methodrefCount=%d; time=%d(ms)\n", jarCount, classCount,
				methodrefCount, System.currentTimeMillis() - start);
	}

	static Manifest readManifest(Path basedir, String relpath) throws IOException {
		try (InputStream is = Files.newInputStream(basedir.resolve(relpath))) {
			return new Manifest(is);
		}
	}

	static void indexJar(Path path, JarFile jar) throws IOException, RocksDBException {
		Enumeration<JarEntry> entries = jar.entries();
		while (entries.hasMoreElements()) {
			JarEntry entry = entries.nextElement();
			if (entry.getName().endsWith(".class")) {
				try (InputStream is = jar.getInputStream(entry)) {
//					indexClass(new ClassReader(is));

					indexClass(path, new DataInputStream(is));
				}
			}
		}
		jarCount++;
	}

	static void indexClass(Path path, DataInputStream dis) throws IOException, RocksDBException {
		classCount++;
		ClassFile cf = new ClassFile(dis);
//		dis.readInt(); // magic 0xCAFEBABE
//		dis.readUnsignedShort(); // minor
//		dis.readUnsignedShort(); // major
//		ConstPool cp = new ConstPool(dis);
		ConstPool cp = cf.getConstPool();
		for (int i = 1; i < cp.getSize(); i++) {
			switch (cp.getTag(i)) {
			case ConstPool.CONST_InterfaceMethodref:
			case ConstPool.CONST_Methodref:
				indexMethodref(path, cf.getName(), cp.getMethodrefClassName(i), cp.getMethodrefName(i));
				break;
			}
		}
		indexClassHierarchy(path, cf.getName(), cf.getSuperclass(), cf.getInterfaces());
	}

	static final byte[] NOBYTES = new byte[0];
	static final Gson gson = new Gson();

	static void indexClassHierarchy(Path path, String classname, String superclass, String[] interfaces)
			throws RocksDBException {
		byte[] key = (classname + "|" + path.toString()).getBytes();
		JsonObject json = new JsonObject();
		if (!"java.lang.Object".equals(superclass)) {
			json.addProperty("super", superclass);
		}
		if (interfaces != null && interfaces.length > 0) {
			json.add("ifaces", gson.toJsonTree(interfaces));
		}
		byte[] value = json.toString().getBytes();
		db.put(columnFamilyHandleList.get(1), writeOptions, key, value);
	}

	static void indexMethodref(Path path, String classname, String targetClass, String targetMethod)
			throws RocksDBException {
		methodrefCount++;
		byte[] key = (targetMethod + "|" + targetClass + "|" + classname + "|" + path.toString()).getBytes();
		byte[] value = NOBYTES;
		db.put(columnFamilyHandleList.get(2), writeOptions, key, value);
	}

	static int jarCount;
	static int classCount;
	static int methodrefCount;
	static RocksDB db;
	static WriteOptions writeOptions;
	static List<ColumnFamilyHandle> columnFamilyHandleList;
}
