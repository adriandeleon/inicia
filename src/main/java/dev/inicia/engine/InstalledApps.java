package dev.inicia.engine;

import java.io.IOException;
import java.util.List;

/** Reads what's installed on this machine. */
public interface InstalledApps {

    List<InstalledApp> list() throws IOException, InterruptedException;
}
