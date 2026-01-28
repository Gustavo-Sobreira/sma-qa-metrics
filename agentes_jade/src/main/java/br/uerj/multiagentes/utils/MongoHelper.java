package br.uerj.multiagentes.utils;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

/**
 * Helper simples para acesso ao MongoDB.
 * Banco padrão: analysis_results
 */
public final class MongoHelper {
    private static MongoClient client;

    private MongoHelper() {}

    public static MongoDatabase getDatabase() {
        if (client == null) {
            String uri = System.getenv("URI_MONGO");
            if (uri == null || uri.isBlank()) {
                uri = "mongodb://mongo:27017";
            }
            client = MongoClients.create(uri);
        }
        return client.getDatabase(System.getenv().getOrDefault("MONGO_DB", "analysis_results"));
    }
}
