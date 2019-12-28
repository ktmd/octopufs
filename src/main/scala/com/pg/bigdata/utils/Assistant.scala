package com.pg.bigdata.utils


import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.catalog.CatalogTable

object Assistant {
  val magicPrefix = ".dfs.core.windows.net"

  def getTableLocation(tableName: String)(implicit spark: SparkSession): String = {
    getTableLocation(spark.catalog.currentDatabase,tableName)
  }

  def getContainerName(uri: String): String = {
    uri.substring(uri.indexOf("//")+2,uri.indexOf("@"))
  }
  def getPathsList(sourceDbName: String, sourceTableName: String,
                   targetDbName: String, targetTableName: String)
                  (implicit spark: SparkSession): Array[Paths] = {
    val sourceLocation = getRelativePath(getTableLocation(sourceDbName,sourceTableName))
    val targetLocation = getRelativePath(getTableLocation(targetDbName,targetTableName))
    println("target location " +targetLocation)
    val sourceFileList = getListOfTableFiles(sourceDbName, sourceTableName)
    if(sourceFileList.isEmpty)
      throw new Exception("There is nothing to be copied")
    val targetFileList = sourceFileList.map(_.replaceAll(sourceLocation, targetLocation))
    println(targetFileList(0))
    sourceFileList.zip(targetFileList).map(x => Paths(x._1,x._2))
  }

  def getListOfTableFiles(sourceDbName: String, sourceTableName: String)(implicit spark: SparkSession): Array[String] = {
    spark.table(sourceDbName + "." + sourceTableName).inputFiles.map(x => getRelativePath(x))

  }

  def getRelativePath(uri: String): String = {
    if(!uri.contains(magicPrefix))
      throw new Exception("MagicPrefix not found")
    uri.substring(uri.indexOf(magicPrefix)+magicPrefix.length)
  }

  def getTableLocation(databaseName: String, tableName: String)(implicit spark: SparkSession): String = {
    getTableMetadata(databaseName, tableName).location.toString
  }

  private def getTableMetadata(databaseName: String, tableName: String)(implicit spark: SparkSession): CatalogTable = {
    spark.sessionState.catalog.getTableMetadata(new TableIdentifier(tableName, Option(databaseName)))
  }

  def refreshMetadata(db: String, table: String)(implicit spark: SparkSession): Unit = {
    val target = db + "." + table
    println("Refreshing metadata for " + target)
    spark.catalog.refreshTable(target)
    if(getTableMetadata(db, table).partitionColumnNames.nonEmpty) {
      println("Recovering partitions for "+target)
      spark.catalog.recoverPartitions(target)
    }

  }

  def filterPartitions(db: String, tableName: String, partitionsToKeepLike: Seq[String])(implicit spark: SparkSession): Array[String] = {
    getTableL1PartitionsPaths(db,tableName).filter(x => partitionsToKeepLike.exists(y => x.contains(y)))
  }

  def getTableL1PartitionsPaths(db: String, tableName: String)(implicit spark: SparkSession): Array[String] = {
    val m = getTableMetadata(db,tableName).partitionColumnNames
    if(m.isEmpty) throw new Exception("Table " + db + "." + tableName + " is not partitioned")
    val absTblLoc = getTableLocation(db, tableName)
    val tblLoc = getRelativePath(absTblLoc)
    val fs = getFileSystem(spark.sparkContext.hadoopConfiguration, absTblLoc)
    val partList = fs.listStatus(new Path(tblLoc))
    partList.filter(_.isDirectory).map(tblLoc + _.getPath.getName)
  }

  def getFileSystem(hadoopConf: Configuration, absoluteTargetLocation: String): FileSystem = {
    hadoopConf.set("fs.defaultFS", getFileSystemPrefix(absoluteTargetLocation))
    FileSystem.get(hadoopConf)
  }

  def getFileSystemPrefix(uri: String): String = {
    if(!uri.contains(magicPrefix))
      throw new Exception("MagicPrefix not found")
    uri.substring(0,uri.indexOf(magicPrefix)+magicPrefix.length)
  }
}
