package com.aditya.filebrowser;

/** Minimal stub for browsemyfiles constants used by this project. */
public final class Constants {
    private Constants() {}

    public static final String SELECTION_MODE = "selection_mode";
    public static final String INITIAL_DIRECTORY = "initial_directory";
    public static final String ALLOWED_FILE_EXTENSIONS = "allowed_file_extensions";

    public enum SELECTION_MODES {
        SINGLE_SELECTION,
        MULTI_SELECTION
    }
}
