package dev.inicia.windows;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * inicia is a console program, so a double-click opens a console window before inicia's own
 * window. Hiding it the moment inicia starts turns the black box that stayed up while inicia
 * started its window into a flicker at most.
 */
public final class ConsoleWindow {

    private static final int SW_HIDE = 0;

    private ConsoleWindow() {
    }

    /**
     * Hides the console when inicia has it to itself (a double-click), never a terminal's that
     * inicia was run from. Anything that goes wrong leaves the console as it is.
     */
    public static void hideIfOwn() {
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", arena);
            MethodHandle processList = linker.downcallHandle(kernel32.find("GetConsoleProcessList").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            MethodHandle consoleWindow = linker.downcallHandle(kernel32.find("GetConsoleWindow").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
            MethodHandle showWindow = linker.downcallHandle(user32.find("ShowWindow").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            // the processes sharing this console: just inicia when Windows made it for the double-click
            MemorySegment ids = arena.allocate(ValueLayout.JAVA_INT, 4);
            int count = (int) processList.invokeExact(ids, 4);
            if (count != 1) {
                return;
            }
            MemorySegment window = (MemorySegment) consoleWindow.invokeExact();
            if (window.address() != 0) {
                int ignored = (int) showWindow.invokeExact(window, SW_HIDE);
            }
        } catch (Throwable e) {
            // no FFM, no console: nothing to hide
        }
    }
}
