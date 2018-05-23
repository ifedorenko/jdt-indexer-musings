package adhoc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class QueryIndex {
	private static final String ROCKSDBDIR = "/tmp/rocksdb-index";

	private static List<ColumnFamilyHandle> columnFamilyHandleList;

	public static void main(String[] args) throws RocksDBException {
		if (args.length != 2) {
			System.out.printf("Usage: %s classname methodname\n", QueryIndex.class.getName());
			System.exit(-1);
		}

		String classname = args[0];
		String methodname = args[1];

		System.out.printf("rocksdb=%s; classname=%s; methodname=%s\n", ROCKSDBDIR, classname, methodname);

		RocksDB.loadLibrary();

		final List<ColumnFamilyDescriptor> columnFamilyDescriptors = new ArrayList<>();
		columnFamilyDescriptors
				.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, new ColumnFamilyOptions()));
		columnFamilyDescriptors.add(new ColumnFamilyDescriptor("parents".getBytes(), new ColumnFamilyOptions()));
		columnFamilyDescriptors.add(new ColumnFamilyDescriptor("methodrefs".getBytes(), new ColumnFamilyOptions()));

		columnFamilyHandleList = new ArrayList<>();

		try (RocksDB db = RocksDB.openReadOnly(ROCKSDBDIR, columnFamilyDescriptors, columnFamilyHandleList)) {
//			findClass(db, classname);
			findMethodrefs(db, classname, methodname);

			for (final ColumnFamilyHandle columnFamilyHandle : columnFamilyHandleList) {
				columnFamilyHandle.close();
			}
		}
	}

	static void findMethodrefs(RocksDB db, String classname, String methodname) {
		long start = System.currentTimeMillis();
		int count = 0;
		int matchCount = 0;
		try (RocksIterator iter = db.newIterator(columnFamilyHandleList.get(2))) {
			String prefix = methodname + "|";
			iter.seek(prefix.getBytes());
			for (; iter.isValid() && new String(iter.key()).startsWith(prefix); iter.next()) {
				count++;
				String key = new String(iter.key());
				HashMap<String, Boolean> knowns = new HashMap<>();
				knowns.put(classname, true);
				if (isChild(db, classname, key.split("\\|")[1], knowns)) {
					matchCount++;
					System.out.println(key);
				}
			}
		}
		System.out.printf("count=%d; matchCount=%d; duration=%d(ms)\n", count, matchCount,
				System.currentTimeMillis() - start);
	}

	static boolean isChild(RocksDB db, String parent, String child, Map<String, Boolean> knowns) {
		Boolean known = knowns.get(child);
		if (known != null) {
			return known;
		}

		JsonParser jparser = new JsonParser();
		try (RocksIterator iter = db.newIterator(columnFamilyHandleList.get(1))) {
			String prefix = child + "|";
			for (iter.seek(prefix.getBytes()); iter.isValid() && new String(iter.key()).startsWith(prefix); iter
					.next()) {
				Set<String> parents = new HashSet<>();
				JsonObject json = (JsonObject) jparser.parse(new String(iter.value()));
				JsonElement superclass = json.get("super");
				if (superclass != null) {
					parents.add(superclass.getAsString());
				}
				JsonElement ifaces = json.get("ifaces");
				if (ifaces != null) {
					for (JsonElement iface : ifaces.getAsJsonArray()) {
						parents.add(iface.getAsString());
					}
				}
				if (parents.contains(parent)) {
					knowns.put(child, true);
					return true;
				}
				for (String superParent : parents) {
					if (isChild(db, parent, superParent, knowns)) {
						knowns.put(child, true);
						return true;
					}
				}
			}
		}

		knowns.put(child, false);
		return false;
	}

	static void findClass(RocksDB db, String classname) {
		try (RocksIterator iter = db.newIterator(columnFamilyHandleList.get(1))) {
			String prefix = classname + "|";
			for (iter.seek(prefix.getBytes()); iter.isValid() && new String(iter.key()).startsWith(prefix); iter
					.next()) {
				System.out.println(new String(iter.key()));
			}
		}
	}
}
