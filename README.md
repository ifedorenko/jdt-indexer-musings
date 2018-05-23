## Improved methodref index POC

### POC goals

* Assess feasibility of using off-the-shelf key/value store for JDT index data
* Estimate disk footprint of methodref name+type and type hierarchy indices
* Estimate results quality and performance of "find method references" query

### TL;DR POC results

* Off-the-shelf key/value database with efficient prefix query appears to be viable java index store
* Acceptable disk footprint
* Very good query performance and result quality

### Little more detailed results

* POC uses [rocksdb](https://rocksdb.org) key/value db
  * osx, linux x32/x64 and win x64 are [supported ootb](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.rocksdb%22)
  * will need to build for ["other" platforms](http://www.eclipse.org/projects/project-plan.php?projectid=eclipse#target_environments) ourselves

* methodref+type and class hierarchy indices of ~3k jars with ~600K classes and ~13M methodrefs take ~500M on disk
  * as a point of reference, "old" jdt index of the same classes is ~1.4G and "new" (aka "google") index is ~2.5GB (google index does notinclude method or field references)
  * need complete indexer implementation to get disk footprint estimates
  * my _guess_, proposed index disk footprint is in the same ballpark as the new/google index

* it takes ~6 minutes to index ~3k jars with ~600K classes on late 2013 15" macbook pro
  * running indexing on multiple CPU cores should improve performance

* query that matches 4 methodrefs takes ~150ms on late 2013 15" macbook pro
  * similar old index query takes about the same time but gives very poor results with >1800 unrelated candidates


### POC high-level idea

* build methodref+type and type hierarchy indices
  * for comparison, the old JDT index only has methodref info (i.e. without type) and the new/google index only has type hierarchy info (i.e. no methodrefs)
* implement "find method references" query as two-steps
  1. find all method references with the given method name
  2. narrow down the results to only include methods from subclasses of the given type

### POC details

#### Indexing

* POC uses [javassist](http://jboss-javassist.github.io/javassist/) to read superclass, interfaces, methodref and interfacemethodref from class files
  * POC does not capture method signatures, which somewhat underestimates index disk footprint
* methodrefs are stored as 4-tuple key and empty value in the index:
  * `targetMethod|targetClass|siteClass|siteJar`
* class hierarchy is stored is
  * key is `class|jar` tuple
  * value is `{super="...", ifaces=[...]}` json

#### Incremental reindexing

Have not implemented. Rough idea:

* maintain `siteClass|siteJar` => `methodref` index (possible as part of class hierarchy value json)
* delete methodref `targetMethod|targetClass|siteClass|siteJar` keys during reindex
* if index disk footprint grows too big, replace `siteClass|siteJar` string with 16 byte `MD5(siteClass|siteJar)` or 32 byte `MD5(siteClass)MD5(siteJar)`
* [messagepack](https://msgpack.org/index.html) is another possible way to reduce type hierarchy index disk footprint

#### "Find method references" query

1. Find all method references with a given name
  * `methodName|` key prefix query is directly supported by rocksdb
2. "Is subtype of" query is implemented by recursivly going up type hierarchy
  * negative result is the most expansive to get, which kinda sucks

  
