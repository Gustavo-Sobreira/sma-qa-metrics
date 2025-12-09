package br.uerj.multiagentes.utils;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

public class MongoHelper {
    private static MongoClient client;

    public static MongoDatabase getDatabase() {
        if (client == null) {
            String uri = System.getenv("MONGO_URI");
            if (uri == null || uri.isEmpty())
                uri = "mongodb://mongo:27017";
            client = MongoClients.create(uri);
        }
        return client.getDatabase("analysis_results");
    }
}