package com.example.demo.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Utility class for file operations
 */
public class FileUtil {

    // Maximum file size to process (1MB)
    private static final long MAX_FILE_SIZE = 1_000_000;

    // Common binary file extensions to skip
    private static final Set<String> BINARY_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jar", "war", "ear", "zip", "tar", "gz", "rar", "7z",
            "jpg", "jpeg", "png", "gif", "bmp", "ico", "svg",
            "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx",
            "class", "so", "dll", "exe", "bin"
    ));

    /**
     * Get the file extension from a file path
     *
     * @param filePath Path to the file
     * @return Extension (without the dot) or empty string if no extension
     */
    public static String getFileExtension(String filePath) {
        if (filePath == null) {
            return "";
        }

        int dotIndex = filePath.lastIndexOf('.');
        if (dotIndex == -1 || dotIndex == filePath.length() - 1) {
            return "";
        }

        return filePath.substring(dotIndex + 1);
    }

    /**
     * Get the file name (without path) from a file path
     *
     * @param filePath Path to the file
     * @return File name
     */
    public static String getFileName(String filePath) {
        if (filePath == null) {
            return "";
        }

        int lastSeparator = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return lastSeparator >= 0 ? filePath.substring(lastSeparator + 1) : filePath;
    }

    /**
     * Check if a file is a text file (not binary)
     *
     * @param path Path to the file
     * @return true if the file is likely a text file, false otherwise
     */
    public static boolean isTextFile(Path path) {
        if (Files.isDirectory(path)) {
            return false;
        }

        String extension = getFileExtension(path.toString()).toLowerCase();
        if (BINARY_EXTENSIONS.contains(extension)) {
            return false;
        }

        try {
            String mime = Files.probeContentType(path);
            if (mime != null) {
                return mime.startsWith("text/") ||
                        mime.equals("application/json") ||
                        mime.equals("application/xml") ||
                        mime.equals("application/javascript");
            }

            // If MIME type detection fails, try to read the first few bytes
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0) {
                return true; // Empty file, treat as text
            }

            // Check for common binary file signatures
            if (bytes.length >= 4) {
                // Check for UTF-8 BOM
                if (bytes[0] == (byte)0xEF && bytes[1] == (byte)0xBB && bytes[2] == (byte)0xBF) {
                    return true;
                }

                // Check for common binary file signatures
                if (bytes[0] == 'P' && bytes[1] == 'K') {
                    return false; // ZIP file
                }

                if (bytes[0] == (byte)0xFF && bytes[1] == (byte)0xD8) {
                    return false; // JPEG
                }

                if (bytes[0] == (byte)0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
                    return false; // PNG
                }
            }

            // Count control characters
            int controlChars = 0;
            for (int i = 0; i < Math.min(bytes.length, 1000); i++) {
                if (bytes[i] < 0x09 || (bytes[i] > 0x0D && bytes[i] < 0x20 && bytes[i] != 0x1B)) {
                    controlChars++;
                }
            }

            // If more than 10% are control chars, likely binary
            return controlChars <= Math.min(bytes.length, 1000) * 0.1;

        } catch (IOException e) {
            return false; // If we can't read the file, assume it's not text
        }
    }

    /**
     * Check if a file is too large to process
     *
     * @param path Path to the file
     * @return true if the file exceeds the maximum size
     */
    public static boolean isFileTooLarge(Path path) {
        try {
            return Files.size(path) > MAX_FILE_SIZE;
        } catch (IOException e) {
            return true; // If we can't determine size, assume it's too large
        }
    }

    /**
     * Read a file's content as a string
     *
     * @param path Path to the file
     * @return Content of the file as a string
     * @throws IOException if the file cannot be read
     */
    public static String readFileContent(Path path) throws IOException {
        return Files.readString(path);
    }

    /**
     * Get the parent path from a file path
     *
     * @param filePath Path to the file
     * @return Parent directory path
     */
    public static String getParentPath(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "";
        }

        int lastSeparator = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return (lastSeparator > 0) ? filePath.substring(0, lastSeparator) : "";
    }
}
