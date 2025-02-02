/* Annot8 (annot8.io) - Licensed under Apache-2.0. */
package io.annot8.components.mongo.resources;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

public class Mongo implements MongoConnection {

  private MongoClient client;
  private MongoDatabase database;
  private MongoCollection<Document> collection;

  public Mongo(MongoConnectionSettings settings) {

    client = MongoClients.create(settings.getConnection());
    database = client.getDatabase(settings.getDatabase());
    collection = database.getCollection(settings.getCollection());
  }

  public MongoDatabase getDatabase() {
    return database;
  }

  public MongoCollection getCollection() {
    return collection;
  }

  @Override
  public void disconnect() {
    if (client != null) {
      client.close();
    }
  }
}
