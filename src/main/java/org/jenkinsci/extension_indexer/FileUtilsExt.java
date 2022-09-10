package org.jenkinsci.extension_indexer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Simple collection of utility stuff for Files.
 *
 * @author Robert Sandell &lt;robert.sandell@sonyericsson.com&gt;
 */
public final class FileUtilsExt extends org.apache.commons.io.FileUtils {

    /**
     * Unzips a zip/jar archive into the specified directory.
     *
     * @param file        the file to unzip
     * @param toDirectory the directory to extract the files to.
     */
    public static void unzip(File file, File toDirectory) throws IOException {
        try (ZipFile zipFile = new ZipFile(file)) {

            Enumeration<? extends ZipEntry> entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();

                if (entry.isDirectory()) {
                    File dir = new File(toDirectory, entry.getName());
                    Files.createDirectories(dir.toPath());
                    continue;
                }
                File entryFile = new File(toDirectory, entry.getName());
                Files.createDirectories(entryFile.getParentFile().toPath());
                copyInputStream(zipFile.getInputStream(entry),
                        new BufferedOutputStream(new FileOutputStream(entryFile)));
            }
        }
    }

    private static void copyInputStream(InputStream in, OutputStream out)
            throws IOException {
        byte[] buffer = new byte[1024];
        int len;

        while ((len = in.read(buffer)) >= 0)
            out.write(buffer, 0, len);

        in.close();
        out.close();
    }

    public static List<File> getFileIterator(File dir, String... extensions) {
        Iterator<File> i = iterateFiles(dir, extensions, true);
        LinkedList<File> l = new LinkedList<>();
        while(i.hasNext()) {
            l.add(i.next());
        }
        return l;
    }
}
