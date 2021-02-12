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
    
    @Autowired
    IlivalidatorService ilivalidator;

    @Autowired
    Ili2gpkgService ili2gpkg;
    
    HashMap<String, File> sessionFileMap = new HashMap<String, File>();
    
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException, IoxException {        
        File file = sessionFileMap.get(session.getId());
        
        String filename = message.getPayload();
        String tempDir = file.getParent();
        log.info(tempDir);

        Path copiedFile = Paths.get(tempDir, filename);
        Files.copy(file.toPath(), copiedFile, StandardCopyOption.REPLACE_EXISTING);
        log.info(copiedFile.toFile().getAbsolutePath());

        session.sendMessage(new TextMessage("Received: " + filename));        
        
        // Daten validierten.
        session.sendMessage(new TextMessage("Validating..."));
        
        boolean valid;
        try {
            valid = ilivalidator.validate(copiedFile.toFile().getAbsolutePath());
        } catch (IOException e) {
            session.sendMessage(new TextMessage("<span style='background-color:#EC7063'>An error occured while validating the data.</span>"));
            return;
        }
        
        String resultText = "<span style='background-color:#58D68D;'>...validation done.</span>";
        if (!valid) {
            resultText = "<span style='background-color:#EC7063'>...validation failed.</span>";
        }

        TextMessage resultMessage = new TextMessage(resultText + "<br/><br/>");
        session.sendMessage(resultMessage);

        // Daten (inkl. Fehler-Logdatei) in eine GeoPackage-Datei importieren.
        session.sendMessage(new TextMessage("Importing..."));
        
        try {
            ili2gpkg.importData(copiedFile.toFile().getAbsolutePath());
        } catch (Ili2dbException e) {
            session.sendMessage(new TextMessage("<span style='background-color:#EC7063'>An error occured while importing the data.</span>"));
            return;
        }
        
        resultText = "<span style='background-color:#58D68D'>...import done.</span>";
        resultMessage = new TextMessage(resultText + "<br/><br/>");
        session.sendMessage(resultMessage);
        
        // Alles zippen und an Client senden.
        String tempDirectory = FilenameUtils.getFullPath(copiedFile.toFile().getAbsolutePath());
        String baseName = FilenameUtils.getBaseName(copiedFile.toFile().getAbsolutePath());
        File zipFile = Paths.get(tempDirectory, baseName + ".zip").toFile();
       
        List<File> filesToAdd = new ArrayList<File>();
        for (String fileName : new File(tempDirectory).list()) {
            if (fileName.contains("gpkg") || fileName.contains("log")) {
                filesToAdd.add(Paths.get(tempDirectory, fileName).toFile());
            }
        }
        
        if (filesToAdd.size() == 0) {
            session.sendMessage(new TextMessage("<span style='background-color:#EC7063'>An error occured: No files found to zip.</span>"));
            return;
        }
        
        new ZipFile(zipFile).addFiles(filesToAdd);
        byte[] fileContent = Files.readAllBytes(zipFile.toPath());
        session.sendMessage(new BinaryMessage(fileContent));


        
        
        //FileUtils.deleteDirectory(new File(tempDirectory));

        
        
        
        
        
//        session.sendMessage(new TextMessage("Importing..."));
//
//        String logFilename = copiedFile.toFile().getAbsolutePath() + ".log";
//        log.info(logFilename);
//        
//        Config settings = createConfig();
//        settings.setFunction(Config.FC_IMPORT);
//        settings.setDoImplicitSchemaImport(true);
//
//        String modelName = null;
//        try {
//            modelName = getModelNameFromTransferFile(copiedFile.toFile().getAbsolutePath());
//            settings.setModels(modelName);
//        } catch (IoxException e) {
//			e.printStackTrace();
//			session.sendMessage(new TextMessage("<span style='background-color:#EC7063;'>...import failed:</span> " + e.getMessage()));
//			sessionFileMap.remove(session.getId());
//			return;
//        }
//
//        // Hardcodiert für altes Naturgefahrenkarten-Modell, damit
//        // nicht eine Koordinatenystemoption im GUI exponiert werden
//        // muss. Mit LV03 wollen wir nichts mehr am Hut haben.    
//        if (modelName.equalsIgnoreCase("Naturgefahrenkarte_SO_V11")) {
//            settings.setDefaultSrsCode("21781");
//            settings.setTidHandling(Config.TID_HANDLING_PROPERTY);
//            settings.setImportTid(true);
//            settings.setCreateFk(Config.CREATE_FK_YES);
//            settings.setCreateEnumDefs(Config.CREATE_ENUM_DEFS_MULTI); 
//            settings.setCreateMetaInfo(true);
//
//        } else {
//            settings.setDefaultSrsCode("2056");
//            settings.setNameOptimization(settings.NAME_OPTIMIZATION_TOPIC);
//            settings.setCreateEnumDefs(Config.CREATE_ENUM_DEFS_MULTI); 
//        }
//
//        String gpkgFileName = copiedFile.toFile().getAbsolutePath().substring(0, copiedFile.toFile().getAbsolutePath().length()-4) + ".gpkg";
//        settings.setDbfile(gpkgFileName);
//        settings.setStrokeArcs(settings, settings.STROKE_ARCS_ENABLE);
//        settings.setValidation(false);
//        
//        if (Ili2db.isItfFilename(copiedFile.toFile().getName())) {
//            settings.setItfTransferfile(true);
//        }
//        
//        settings.setDburl("jdbc:sqlite:" + settings.getDbfile());
//        settings.setXtffile(copiedFile.toFile().getAbsolutePath());
//
//        try {
//			Ili2db.run(settings, null);
//		} catch (Ili2dbException e) {
//			e.printStackTrace();
//			session.sendMessage(new TextMessage("<span style='background-color:#58D68D;'>...import failed.</span>"));
//			sessionFileMap.remove(session.getId());
//			return;
//		}
//
//        // Kopieren des vordefinierten QGIS-Projekt in die GeoPackage-Datei.
//        Resource resource = resourceLoader.getResource("classpath:datenkontrolle.qgs");
//        InputStream inputStream = resource.getInputStream();
//        
//        File qgsFile = new File(Paths.get(tempDir, "datenkontrolle.qgs").toFile().getAbsolutePath());
//        log.info(qgsFile.getAbsolutePath());
//        Files.copy(inputStream, qgsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
//        inputStream.close();
//
//        // Man muss zusätzlich den Namen der GPKG-Datei in der QGIS-Projektdatei ersetzen. Die erstellte GPKG-Datei
//        // heisst nicht immer gleich.
//        String oldFileContent = new String(Files.readAllBytes(qgsFile.toPath()), StandardCharsets.UTF_8);
//        String newFileContent = oldFileContent.replaceAll("./GKSO11.gpkg", "./" + new File(gpkgFileName).getName());
//        FileWriter writer = new FileWriter(qgsFile);
//        writer.write(newFileContent);
//        writer.close();
//        
//        // QGS -> QGZ
//		FileOutputStream fos = new FileOutputStream(Paths.get(tempDir, "datenkontrolle.qgz").toFile().getAbsolutePath());
//		ZipOutputStream zipOut = new ZipOutputStream(fos);
//		FileInputStream fis = new FileInputStream(qgsFile);
//		ZipEntry zipEntry = new ZipEntry(qgsFile.getName());
//		zipOut.putNextEntry(zipEntry);
//		byte[] bytes = new byte[1024];
//		int length;
//		while ((length = fis.read(bytes)) >= 0) {
//			zipOut.write(bytes, 0, length);
//		}
//		zipOut.close();
//		fis.close();
//		fos.close();
//        
//        byte[] content = Files.readAllBytes(Paths.get(tempDir, "datenkontrolle.qgz"));
//
//        String url = "jdbc:sqlite:" + settings.getDbfile();
//        try (Connection conn = DriverManager.getConnection(url); Statement stmt = conn.createStatement()) {
//        	stmt.execute("CREATE TABLE qgis_projects(name TEXT PRIMARY KEY, metadata BLOB, content BLOB)");
//        	
//        	String name = "datenkontrolle";
//        	DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
//        	String formattedDate = formatter.format(LocalDateTime.now());
//        	String metadata = "{\"last_modified_time\": \""+formattedDate+"\", \"last_modified_user\": \"ili2gpkg\" }";
//        	
//        	String sql = "INSERT INTO qgis_projects (name,metadata,content) VALUES ('"+name+"', '"+ metadata +"', '"+ byteArrayToHex(content) +"')";
//        	stmt.execute(sql);
//        } catch (SQLException e) {
//			session.sendMessage(new TextMessage("<span style='background-color:#58D68D;'>...import failed.</span>"));
//			sessionFileMap.remove(session.getId());
//			return;            
//        }
//       
//        session.sendMessage(new TextMessage("<span style='background-color:#58D68D;'>...import done.</span>"));
//        
//        byte[] fileContent = Files.readAllBytes(new File(gpkgFileName).toPath());
//        session.sendMessage(new BinaryMessage(fileContent));
        
        sessionFileMap.remove(session.getId());
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
    
    private Config createConfig() {
        Config settings = new Config();
        new GpkgMain().initConfig(settings);
        return settings;
    }
    
    private String getModelNameFromTransferFile(String transferFileName) throws IoxException {
        String model = null;
        String ext = getExtensionByString(transferFileName).orElseThrow(IoxException::new);
        
        IoxReader ioxReader = null;

        try {
            File transferFile = new File(transferFileName);

            if (ext.equalsIgnoreCase("itf")) {
                ioxReader = new ItfReader(transferFile);
            } else {
                ioxReader = new XtfReader(transferFile);
            }

            IoxEvent event;
            StartBasketEvent be = null;
            do {
                event = ioxReader.read();
                if (event instanceof StartBasketEvent) {
                    be = (StartBasketEvent) event;
                    break;
                }
            } while (!(event instanceof EndTransferEvent));

            ioxReader.close();
            ioxReader = null;

            if (be == null) {
                throw new IllegalArgumentException("no baskets in transfer-file");
            }

            String namev[] = be.getType().split("\\.");
            model = namev[0];

        } catch (IoxException e) {
            log.error(e.getMessage());
            e.printStackTrace();
            throw new IoxException("could not parse file: " + new File(transferFileName).getName());
        } finally {
            if (ioxReader != null) {
                try {
                    ioxReader.close();
                } catch (IoxException e) {
                    log.error(e.getMessage());
                    e.printStackTrace();
                    throw new IoxException(
                            "could not close interlis transfer file: " + new File(transferFileName).getName());
                }
                ioxReader = null;
            }
        }
        return model;
    } 
    
    private Optional<String> getExtensionByString(String filename) {
        return Optional.ofNullable(filename)
                .filter(f -> f.contains("."))
                .map(f -> f.substring(filename.lastIndexOf(".") + 1));
    }
    
	public static String byteArrayToHex(byte[] a) {
		StringBuilder sb = new StringBuilder(a.length * 2);
		for (byte b : a)
			sb.append(String.format("%02x", b));
		return sb.toString();
	}
}