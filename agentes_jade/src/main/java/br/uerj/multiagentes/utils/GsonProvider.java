package br.uerj.multiagentes.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Centraliza configuração de JSON para todos os agentes. */
public final class GsonProvider {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private GsonProvider() {}

    public static Gson get() {
        return GSON;
    }
}
