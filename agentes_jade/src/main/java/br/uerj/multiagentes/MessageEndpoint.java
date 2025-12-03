package br.uerj.multiagentes;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import br.uerj.multiagentes.utils.AgentMessenger;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.OutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Type;
import java.util.Map;

public class MessageEndpoint {

    private static Gson gson = new Gson();

    public static void start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

            server.createContext("/message", (HttpExchange exchange) -> {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {

                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

                    Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
                    Map<String, Object> json = gson.fromJson(body, mapType);

                    String to = (String) json.get("to");
                    Object payload = json.get("payload");

                    AgentMessenger.send(to, gson.toJson(payload));

                    String resp = "OK";
                    exchange.sendResponseHeaders(200, resp.length());
                    OutputStream os = exchange.getResponseBody();
                    os.write(resp.getBytes());
                    os.close();

                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
            });

            server.start();
            System.out.println("HTTP endpoint em http://localhost:8080/message");

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
