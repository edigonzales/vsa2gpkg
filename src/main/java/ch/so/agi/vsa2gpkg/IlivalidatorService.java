package ch.so.agi.vsa2gpkg;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.io.FilenameUtils;
import org.interlis2.validator.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import ch.ehi.basics.settings.Settings;
import ch.interlis.iox.IoxException;

@Service
public class IlivalidatorService {
    private final Logger log = LoggerFactory.getLogger(this.getClass());
    
    private String CONFIG_FILE_NAME = "vsadssmini_2020_lv95.toml";
        
    public boolean validate(String inputFileName) throws IoxException, IOException {
        String tempDirectory = FilenameUtils.getFullPath(inputFileName);
        String baseName = FilenameUtils.getBaseName(inputFileName);
        String logFileName = Paths.get(tempDirectory, baseName + ".log").toFile().getAbsolutePath();
        String xtfLogFileName = Paths.get(tempDirectory, baseName + "_log.xtf").toFile().getAbsolutePath();
        
        Settings settings = new Settings();
        settings.setValue(Validator.SETTING_LOGFILE, logFileName);
        settings.setValue(Validator.SETTING_XTFLOG, xtfLogFileName);
        
        // TODO: Lokale Kopie der Modelle, falls was am VSA-Repo rumgedoktert wird.
        settings.setValue(Validator.SETTING_ILIDIRS, "https://vsa.ch/models;%ITF_DIR");

        // TODO: to be discussed
        //settings.setValue(Validator.SETTING_ALL_OBJECTS_ACCESSIBLE, Validator.TRUE);

        // Alle INTERLIS-Modelle, die lokal vorgehalten werden (z.B. zusätzliche Validierungsmodelle
        // oder die lokalen Kopien des Originalmodells), werden in das Temp-Verzeichnis kopiert.
        // Alle Config-Dateien (aka toml files) werden kopiert.
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] ilis = resolver.getResources("classpath:ili/*.ili");
        Resource[] tomls = resolver.getResources("classpath:toml/*.toml");
        List<Resource> resources = new ArrayList<Resource>();
        resources.addAll(Arrays.asList(ilis));
        resources.addAll(Arrays.asList(tomls));
      
        log.info("Found " + String.valueOf(ilis.length) + " local models.");
        log.info("Found " + String.valueOf(tomls.length) + " config files.");
      
        for (Resource resource : resources) {
            try (InputStream is = resource.getInputStream()) {
                File iliFile = new File(tempDirectory, resource.getFilename());
                log.info(iliFile.getAbsolutePath());
                Files.copy(is, iliFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        
        settings.setValue(Validator.SETTING_CONFIGFILE, Paths.get(tempDirectory, this.CONFIG_FILE_NAME).toFile().getAbsolutePath());

        log.info("Validation start.");
        boolean valid = Validator.runValidation(inputFileName, settings);
        log.info("Validation end.");
        
        return valid;
    }
}
