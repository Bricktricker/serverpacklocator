package cpw.mods.forge.serverpacklocator.client;

import cpw.mods.forge.serverpacklocator.FileChecksumValidator;
import cpw.mods.forge.serverpacklocator.LaunchEnvironmentHandler;
import cpw.mods.forge.serverpacklocator.ServerManifest;
import cpw.mods.forge.serverpacklocator.secure.IConnectionSecurityManager;
import cpw.mods.modlauncher.api.LamdbaExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class SimpleHttpClient {
    private static final Logger LOGGER = LogManager.getLogger();
    private final Path outputDir;
    private ServerManifest serverManifest;
    private Iterator<ServerManifest.ModFileData> fileDownloaderIterator;
    private final Future<Boolean> downloadJob;
    private final IConnectionSecurityManager connectionSecurityManager;
    private final List<String> excludedModIds;
    
    private byte[] challenge;

    public SimpleHttpClient(final ClientSidedPackHandler packHandler, final IConnectionSecurityManager connectionSecurityManager, final List<String> excludedModIds) {
        this.outputDir = packHandler.getServerModsDir();
        this.connectionSecurityManager = connectionSecurityManager;
        this.excludedModIds = excludedModIds;

        final Optional<String> remoteServer = packHandler.getConfig().getOptional("client.remoteServer");
        downloadJob = Executors.newSingleThreadExecutor().submit(() -> remoteServer
          .map(server -> server.endsWith("/") ? server.substring(0, server.length() - 1) : server)
          .map(this::connectAndDownload).orElse(false));
    }

    private boolean connectAndDownload(final String server) {
        try {
        	downloadChallenge(server);
            downloadManifest(server);
            downloadNextFile(server);
            return true;
        } catch (Exception ex) {
            LOGGER.error("Failed to download modpack from server: " + server, ex);
            return false;
        }
    }
    
    protected void downloadChallenge(final String serverHost) throws IOException
    {
    	var address = serverHost + "/challenge";
    	
    	LOGGER.info("Requesting challenge from: " + serverHost);
    	LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Requesting Challenge from: " + serverHost);
    	
    	var url = new URL(address);
        var connection = url.openConnection();
        
        try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
        	final String challengeStr = in.readLine();
        	LOGGER.info("Got Challenge {}", challengeStr);
        	challenge = Base64.getDecoder().decode(challengeStr);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to download challenge", e);
        }
        LOGGER.debug("Received challenge");
    }

    protected void downloadManifest(final String serverHost) throws IOException
    {
        var address = serverHost + "/servermanifest.json";

        LOGGER.info("Requesting server manifest from: " + serverHost);
        LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Requesting server manifest from: " + serverHost);

        var url = new URL(address);
        var connection = url.openConnection();
        this.connectionSecurityManager.onClientConnectionCreation(connection, challenge);

        try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream())) {
            this.serverManifest = ServerManifest.loadFromStream(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to download manifest", e);
        }
        LOGGER.debug("Received manifest");
        buildFileFetcher();
    }

    private void downloadFile(final String server, final ServerManifest.ModFileData next) throws IOException
    {
        final String existingChecksum = FileChecksumValidator.computeChecksumFor(outputDir.resolve(next.getFileName()));
        if (Objects.equals(next.getChecksum(), existingChecksum)) {
            LOGGER.debug("Found existing file {} - skipping", next.getFileName());
            downloadNextFile(server);
            return;
        }

        final String nextFile = next.getFileName();
        LOGGER.info("Requesting file {}", nextFile);
        LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Requesting file "+nextFile);
        final String requestUri = server + LamdbaExceptionUtils.rethrowFunction((String f) -> URLEncoder.encode(f, StandardCharsets.UTF_8.name()))
          .andThen(s -> s.replaceAll("\\+", "%20"))
          .andThen(s -> "/files/"+s)
          .apply(nextFile);

        try
        {
            URLConnection connection = new URL(requestUri).openConnection();
            this.connectionSecurityManager.onClientConnectionCreation(connection, challenge);

            File file = outputDir.resolve(next.getFileName()).toFile();

            FileChannel download = new FileOutputStream(file).getChannel();

            long totalBytes = connection.getContentLengthLong(), time = System.nanoTime(), between, length;
            int percent;

            ReadableByteChannel channel = Channels.newChannel(connection.getInputStream());

            while (download.transferFrom(channel, file.length(), 1024) > 0)
            {
                between = System.nanoTime() - time;

                if (between < 1000000000) continue;

                length = file.length();

                percent = (int) ((double) length / ((double) totalBytes == 0.0 ? 1.0 : (double) totalBytes) * 100.0);

                LOGGER.info("Downloaded {}% of {}", percent, nextFile);
                LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Downloaded " + percent + "% of " + nextFile);

                time = System.nanoTime();
            }

            downloadNextFile(server);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to download file: " + nextFile, ex);
        }
    }

    private void downloadNextFile(final String server) throws IOException
    {
        final Iterator<ServerManifest.ModFileData> fileDataIterator = fileDownloaderIterator;
        if (fileDataIterator.hasNext()) {
            downloadFile(server, fileDataIterator.next());
        } else {
            LOGGER.info("Finished downloading closing channel");
        }
    }

    private void buildFileFetcher() {
        if (this.excludedModIds.isEmpty())
        {
            fileDownloaderIterator = serverManifest.getFiles().iterator();
        }
        else
        {
            fileDownloaderIterator = serverManifest.getFiles()
                                   .stream()
                                   .filter(modFileData -> !this.excludedModIds.contains(modFileData.getRootModId()))
                                   .iterator();
        }

    }

    boolean waitForResult() throws ExecutionException {
        try {
            return downloadJob.get();
        } catch (InterruptedException e) {
            return false;
        }
    }

    public ServerManifest getManifest() {
        return this.serverManifest;
    }
}
