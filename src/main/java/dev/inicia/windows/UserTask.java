package dev.inicia.windows;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Runs a program as the signed-in Windows user, without administrator rights, from an elevated
 * inicia: some installers refuse to run elevated (Spotify's exits with 23). A one-off scheduled
 * task in the user's session runs it, inicia waits for it and reads its exit code, and the task is
 * removed. The user is the owner of the session's Explorer, so with an administrator who isn't the
 * signed-in user (UAC's "over the shoulder" prompt) the app still installs for the person at the PC.
 */
public final class UserTask {

    private UserTask() {
    }

    private static final String SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $session = (Get-Process -Id $PID).SessionId
            $shell = Get-CimInstance Win32_Process -Filter "Name='explorer.exe'" |
              Where-Object { $_.SessionId -eq $session } | Select-Object -First 1
            if (-not $shell) { 'no-user'; exit 0 }
            $owner = Invoke-CimMethod -InputObject $shell -MethodName GetOwner
            $user = "$($owner.Domain)\\$($owner.User)"
            $name = 'inicia-install-' + [guid]::NewGuid().ToString('N')
            $action = if ($env:INICIA_ARGS) { New-ScheduledTaskAction -Execute $env:INICIA_FILE -Argument $env:INICIA_ARGS }
                      else { New-ScheduledTaskAction -Execute $env:INICIA_FILE }
            $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
            $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Minutes $env:INICIA_MINUTES)
            Register-ScheduledTask -TaskName $name -Action $action -Principal $principal -Settings $settings | Out-Null
            try {
              Start-ScheduledTask -TaskName $name
              $deadline = (Get-Date).AddMinutes([int]$env:INICIA_MINUTES)
              do {
                Start-Sleep -Milliseconds 700
                $state = (Get-ScheduledTask -TaskName $name).State
                $result = (Get-ScheduledTaskInfo -TaskName $name).LastTaskResult
                # 267011 (SCHED_S_TASK_HAS_NOT_RUN): it hasn't started yet
              } while (($state -eq 'Running' -or $state -eq 'Queued' -or $result -eq 267011) -and (Get-Date) -lt $deadline)
              if ($state -eq 'Running') { 'timed-out' } else { "exit $result" }
            } finally {
              Unregister-ScheduledTask -TaskName $name -Confirm:$false
            }
            """;

    /**
     * Runs the command as the signed-in user and waits for it.
     *
     * @return its exit code; empty when nobody is signed in to this session (a service, CI), so the
     *         caller runs it the usual way
     */
    public static OptionalInt run(List<String> command, Duration timeout) throws IOException, InterruptedException {
        String out = PowerShell.run(SCRIPT, Map.of(
                "INICIA_FILE", Path.of(command.getFirst()).toString(),
                "INICIA_ARGS", Elevation.commandLine(command.subList(1, command.size())),
                "INICIA_MINUTES", Long.toString(Math.max(1, timeout.toMinutes())))).strip();
        if (out.equals("no-user")) {
            return OptionalInt.empty();
        }
        if (out.equals("timed-out")) {
            throw new IOException("the installer was still running after " + timeout.toMinutes() + " minutes");
        }
        if (!out.startsWith("exit ")) {
            throw new IOException("couldn't run the installer as the signed-in user: " + out);
        }
        return OptionalInt.of((int) Long.parseLong(out.substring(5).strip()));
    }
}
