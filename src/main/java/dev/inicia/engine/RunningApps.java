package dev.inicia.engine;

import java.io.IOException;
import java.util.List;

/** Finds an app's programs running, so its installer doesn't run while they're open. */
public interface RunningApps {

    /** The file names (vlc.exe) of running programs inside any of these folders. */
    List<String> runningIn(List<String> folders) throws IOException, InterruptedException;

    static RunningApps none() {
        return folders -> List.of();
    }
}
