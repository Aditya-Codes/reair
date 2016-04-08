package com.airbnb.di.misc;

import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TJSONProtocol;

public class DeserializeTable {

  /**
   * TODO.
   *
   * @param argv TODO
   *
   * @throws Exception TODO
   */
  public static void main(String[] argv) throws Exception {
    String json = argv[0];
    TDeserializer deserializer = new TDeserializer(new TJSONProtocol.Factory());
    Table table = new Table();
    deserializer.deserialize(table, json, "UTF-8");
    System.out.println("Object is " + table);
  }
}
