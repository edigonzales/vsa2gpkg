package ch.so.agi.vsa2gpkg;

import java.io.IOException;
import java.nio.file.Paths;

import org.apache.commons.io.FilenameUtils;
import org.interlis2.validator.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ch.ehi.ili2db.base.Ili2db;
import ch.ehi.ili2db.base.Ili2dbException;
import ch.ehi.ili2db.gui.Config;
import ch.ehi.ili2gpkg.GpkgMain;
import ch.interlis.iox.IoxException;

@Service
public class Ili2gpkgService {
    private final Logger log = LoggerFactory.getLogger(this.getClass());
    
    private String DATA_MODEL_NAME = "VSADSSMINI_2020_LV95";
    private String VALIDATOR_MODEL_NAME = "IliVErrors";

    public synchronized void importData(String inputFileName) throws Ili2dbException {
        String tempDirectory = FilenameUtils.getFullPath(inputFileName);
        String baseName = FilenameUtils.getBaseName(inputFileName);

//        {
//            Config settings = createConfig();
//            
//            settings.setFunction(Config.FC_IMPORT);
//            settings.setModels(DATA_MODEL_NAME + ";" + VALIDATOR_MODEL_NAME);        
//            settings.setModeldir("https://vsa.ch/models;" + Validator.SETTING_DEFAULT_ILIDIRS);
//            settings.setItfTransferfile(false);
//            
//            settings.setDoImplicitSchemaImport(true);
//            settings.setDefaultSrsCode("2056");
//            settings.setNameOptimization(settings.NAME_OPTIMIZATION_TOPIC);
//            Config.setStrokeArcs(settings, Config.STROKE_ARCS_ENABLE);
//            settings.setCreateEnumDefs(Config.CREATE_ENUM_DEFS_MULTI); 
//            settings.setTidHandling(Config.TID_HANDLING_PROPERTY);
//            settings.setImportTid(true);
//            settings.setImportBid(true);
//            
//            settings.setValidation(false);
//            settings.setSqlNull(Config.SQL_NULL_ENABLE);
//            settings.setSkipGeometryErrors(true);
//            settings.setSkipReferenceErrors(true);            
//
//            String gpkgFileName = inputFileName.substring(0, inputFileName.length()-4) + ".gpkg";
//            settings.setDbfile(gpkgFileName);        
//            settings.setDburl("jdbc:sqlite:" + settings.getDbfile());
//            
//            settings.setXtffile(inputFileName);
//            Ili2db.run(settings, null);
//        }
        
        Config settings = createConfig();
        
        settings.setFunction(Config.FC_IMPORT);
        settings.setModels(DATA_MODEL_NAME + ";" + VALIDATOR_MODEL_NAME);        
        settings.setModeldir("https://vsa.ch/models;" + Validator.SETTING_DEFAULT_ILIDIRS);
        settings.setItfTransferfile(false);
        
        settings.setDoImplicitSchemaImport(true);
        settings.setDefaultSrsCode("2056");
        settings.setNameOptimization(settings.NAME_OPTIMIZATION_TOPIC);
        Config.setStrokeArcs(settings, Config.STROKE_ARCS_ENABLE);
        settings.setCreateEnumDefs(Config.CREATE_ENUM_DEFS_MULTI); 
        settings.setTidHandling(Config.TID_HANDLING_PROPERTY);
        settings.setImportTid(true);
        settings.setImportBid(true);
        
        settings.setValidation(false);
        settings.setSqlNull(Config.SQL_NULL_ENABLE);
        settings.setSkipGeometryErrors(true);
        settings.setSkipReferenceErrors(true);            

        String gpkgFileName = inputFileName.substring(0, inputFileName.length()-4) + ".gpkg";
        settings.setDbfile(gpkgFileName);        
        settings.setDburl("jdbc:sqlite:" + settings.getDbfile());
        
        settings.setXtffile(inputFileName);
        Ili2db.run(settings, null);
        
        settings.setDoImplicitSchemaImport(false);
        settings.setModels(VALIDATOR_MODEL_NAME);        
        settings.setModeldir(Validator.SETTING_DEFAULT_ILIDIRS);
        settings.setXtffile(Paths.get(tempDirectory, baseName + "_log.xtf").toFile().getAbsolutePath());
        Ili2db.run(settings, null);
        
    }
    
    private Config createConfig() {
        Config settings = new Config();
        new GpkgMain().initConfig(settings);
        return settings;
    }
}
