package ch.so.agi.vsa2gpkg;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import ch.interlis.iox.IoxException;
import ch.interlis.iox.IoxReader;
import ch.ehi.ili2db.base.Ili2db;
import ch.ehi.ili2db.base.Ili2dbException;
import ch.ehi.ili2db.gui.Config;
import ch.ehi.ili2gpkg.GpkgMain;
import ch.interlis.iom_j.itf.ItfReader;
import ch.interlis.iom_j.xtf.XtfReader;
import ch.interlis.iox.IoxEvent;
import ch.interlis.iox_j.EndTransferEvent;
import ch.interlis.iox_j.StartBasketEvent;
import ch.so.agi.vsa2gpkg.IlivalidatorService;
import net.lingala.zip4j.ZipFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class WebSocketHandler extends AbstractWebSocketHandler {    
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private static String FOLDER_PREFIX = "vsa2gpkg_";
    private static String GREEN_HEX_COLOR = "#58D68D";
    private static String RED_HEX_COLOR = "#EC7063"; 
    
    @Autowired
    private IlivalidatorService ilivalidator;

    @Autowired
    private Ili2gpkgService ili2gpkg;
    
    @Autowired
    private ResourceLoader resourceLoader;
    
    private HashMap<String, File> sessionFileMap = new HashMap<String, File>();
    
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException  {        
        File file = sessionFileMap.get(session.getId());
        
        String filename = message.getPayload();
        String tempDir = file.getParent();
        log.info(tempDir);
        
        try {
            Path copiedFile = Paths.get(tempDir, filename);
            Files.copy(file.toPath(), copiedFile, StandardCopyOption.REPLACE_EXISTING);
            log.info(copiedFile.toFile().getAbsolutePath());

            session.sendMessage(new TextMessage("Received: " + filename));        
            
            // TODO: Ask Claude.
            synchronized(this) {
                // Daten validieren.
                session.sendMessage(new TextMessage("Validating..."));
                
                boolean valid;
                try {
                    valid = ilivalidator.validate(copiedFile.toFile().getAbsolutePath());
                } catch (IOException | IoxException e) {
                    session.sendMessage(new TextMessage("<span style='background-color:"+RED_HEX_COLOR+";'>An error occured while validating the data.</span></br></br>"));
                    sessionFileMap.remove(session.getId());
                    sendZip(session, copiedFile.toFile());
                    FileUtils.deleteDirectory(new File(tempDir));
                    return;
                }
                
                String resultText = "<span style='background-color:"+GREEN_HEX_COLOR+";'>...validation done.</span>";
                if (!valid) {
                    resultText = "<span style='background-color:"+RED_HEX_COLOR+";'>...validation failed.</span>";
                }
    
                TextMessage resultMessage = new TextMessage(resultText);
                session.sendMessage(resultMessage);
    
                // Daten (inkl. Fehler-Logdatei) in eine GeoPackage-Datei importieren.
                session.sendMessage(new TextMessage("Importing..."));
                
                try {
                    ili2gpkg.importData(copiedFile.toFile().getAbsolutePath());
                    
                    resultText = "<span style='background-color:"+GREEN_HEX_COLOR+";'>...import done.</span>";
                    resultMessage = new TextMessage(resultText);
                    session.sendMessage(resultMessage);
                    
                    // Kopieren des vordefinierten QGIS-Projekt in die GeoPackage-Datei.
                    Resource resource = resourceLoader.getResource("classpath:qgs/datenkontrolle.qgs");
                    InputStream inputStream = resource.getInputStream();

                    File qgsFile = new File(Paths.get(tempDir, "datenkontrolle.qgs").toFile().getAbsolutePath());
                    Files.copy(inputStream, qgsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    inputStream.close();

                    session.sendMessage(new TextMessage("Updating..."));

                    String baseName = FilenameUtils.getBaseName(copiedFile.toFile().getAbsolutePath());
                    String gpkgFileName = Paths.get(tempDir, baseName + ".gpkg").toFile().getAbsolutePath();
                    
                    String oldFileContent = new String(Files.readAllBytes(qgsFile.toPath()), StandardCharsets.UTF_8);
                    String newFileContent = oldFileContent.replaceAll("./VSADSSMINI2020.gpkg", "./" + baseName + ".gpkg");
                    FileWriter writer = new FileWriter(qgsFile);
                    writer.write(newFileContent);
                    writer.close();

                    // QGS -> QGZ
                    FileOutputStream fos = new FileOutputStream(Paths.get(tempDir, "datenkontrolle.qgz").toFile().getAbsolutePath());
                    ZipOutputStream zipOut = new ZipOutputStream(fos);
                    FileInputStream fis = new FileInputStream(qgsFile);
                    ZipEntry zipEntry = new ZipEntry(qgsFile.getName());
                    zipOut.putNextEntry(zipEntry);
                    byte[] bytes = new byte[1024];
                    int length;
                    while ((length = fis.read(bytes)) >= 0) {
                        zipOut.write(bytes, 0, length);
                    }
                    zipOut.close();
                    fis.close();
                    fos.close();

                    byte[] content = Files.readAllBytes(Paths.get(tempDir, "datenkontrolle.qgz"));
                    
                    String url = "jdbc:sqlite:" + gpkgFileName;
                    try (Connection conn = DriverManager.getConnection(url); Statement stmt = conn.createStatement()) {
                        stmt.execute("CREATE TABLE qgis_projects(name TEXT PRIMARY KEY, metadata BLOB, content BLOB)");

                        String name = "datenkontrolle";
                        DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
                        String formattedDate = formatter.format(LocalDateTime.now());
                        String metadata = "{\"last_modified_time\": \"" + formattedDate
                                + "\", \"last_modified_user\": \"ili2gpkg\" }";

                        String sql = "INSERT INTO qgis_projects (name,metadata,content) VALUES ('" + name + "', '" + metadata
                                + "', '" + byteArrayToHex(content) + "')";
                        stmt.execute(sql);
                        session.sendMessage(new TextMessage("<span style='background-color:"+GREEN_HEX_COLOR+";'>...update done.</span><br/><br/>"));
                    } catch (SQLException e) {
                        session.sendMessage(new TextMessage("<span style='background-color:"+RED_HEX_COLOR+";'>...update failed.</span><br/><br/>"));
                    }

                } catch (Ili2dbException e) {
                    session.sendMessage(new TextMessage("<span style='background-color:"+RED_HEX_COLOR+";'>An error occured while importing the data.</span>"+e.getMessage()+"</br></br>"));
                    //sessionFileMap.remove(session.getId());
                    //return;
                }
                
                sessionFileMap.remove(session.getId());
                sendZip(session, copiedFile.toFile());
                FileUtils.deleteDirectory(new File(tempDir));
            }
        } catch (IOException e) {
            e.printStackTrace();
            sessionFileMap.remove(session.getId());
            session.sendMessage(new TextMessage("<span style='background-color:"+RED_HEX_COLOR+"'>An error occured while proccessing the data.</span>"+e.getMessage()+"</br></br>"));
        }

    }
    
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        Path tmpDirectory = Files.createTempDirectory(FOLDER_PREFIX);
        
        // Der Dateinamen ist nicht in der binary message greifbar. Aus diesem Grund
        // wird zuerst die binary message als 'data.file' gespeichert und anschliessend
        // wieder in umbenannt. Der Originalnamen wird als text message geschickt.
        Path uploadFilePath = Paths.get(tmpDirectory.toString(), "data.file"); 
                
        FileChannel fc = new FileOutputStream(uploadFilePath.toFile().getAbsoluteFile(), false).getChannel();
        fc.write(message.getPayload());
        fc.close();

        File file = uploadFilePath.toFile();
        
        sessionFileMap.put(session.getId(), file);
    }
    
    private void sendZip(WebSocketSession session, File file) throws IOException {
        String tempDir = file.getParent();
        String baseName = FilenameUtils.getBaseName(file.getAbsolutePath());
        
        File zipFile = Paths.get(tempDir, baseName + ".zip").toFile();
       
        List<File> filesToAdd = new ArrayList<File>();
        for (String fileName : new File(tempDir).list()) {
            if (fileName.contains("gpkg") || fileName.contains("log")) {
                filesToAdd.add(Paths.get(tempDir, fileName).toFile());
            }
        }
        
        if (filesToAdd.size() == 0) {
            session.sendMessage(new TextMessage("<span style='background-color:#EC7063'>An error occured: No files found to zip.</span></br></br>"));
            sessionFileMap.remove(session.getId());
            FileUtils.deleteDirectory(new File(tempDir));
            return;
        }
        
        new ZipFile(zipFile).addFiles(filesToAdd);
        byte[] fileContent = Files.readAllBytes(zipFile.toPath());
        session.sendMessage(new BinaryMessage(fileContent));
        
        //sessionFileMap.remove(session.getId());
        //FileUtils.deleteDirectory(new File(tempDirectory));
    }
        
	private static String byteArrayToHex(byte[] a) {
		StringBuilder sb = new StringBuilder(a.length * 2);
		for (byte b : a)
			sb.append(String.format("%02x", b));
		return sb.toString();
	}
}