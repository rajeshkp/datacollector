/**
 * Copyright 2015 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.destination.hdfs;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.streamsets.pipeline.api.Batch;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.BaseTarget;
import com.streamsets.pipeline.api.base.OnRecordErrorException;
import com.streamsets.pipeline.api.el.ELEval;
import com.streamsets.pipeline.api.el.ELEvalException;
import com.streamsets.pipeline.api.el.ELVars;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.config.CsvHeader;
import com.streamsets.pipeline.config.CsvMode;
import com.streamsets.pipeline.config.DataFormat;
import com.streamsets.pipeline.config.JsonMode;
import com.streamsets.pipeline.lib.el.RecordEL;
import com.streamsets.pipeline.lib.el.TimeNowEL;
import com.streamsets.pipeline.lib.generator.DataGeneratorFactory;
import com.streamsets.pipeline.lib.generator.DataGeneratorFactoryBuilder;
import com.streamsets.pipeline.lib.generator.avro.AvroDataGeneratorFactory;
import com.streamsets.pipeline.lib.generator.delimited.DelimitedDataGeneratorFactory;
import com.streamsets.pipeline.lib.generator.text.TextDataGeneratorFactory;
import com.streamsets.pipeline.stage.destination.hdfs.writer.ActiveRecordWriters;
import com.streamsets.pipeline.stage.destination.hdfs.writer.RecordWriter;
import com.streamsets.pipeline.stage.destination.hdfs.writer.RecordWriterManager;

import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RawLocalFileSystem;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authentication.util.KerberosUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Subject;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

public class HdfsTarget extends BaseTarget {
  private final static Logger LOG = LoggerFactory.getLogger(HdfsTarget.class);
  private final static int MEGA_BYTE = 1024 * 1024;

  private final String hdfsUri;
  private final String hdfsUser;
  private final boolean hdfsKerberos;
  private final String hadoopConfDir;
  private final Map<String, String> hdfsConfigs;
  private String uniquePrefix;
  private final String dirPathTemplate;
  private final String timeZoneID;
  private final String timeDriver;
  private final long maxRecordsPerFile;
  private final long maxFileSizeMBs;
  private final CompressionMode compression;
  private final String otherCompression;
  private final HdfsFileType fileType;
  private final String keyEl;
  private final HdfsSequenceFileCompressionType seqFileCompressionType;
  private final String lateRecordsLimit;
  private final LateRecordsAction lateRecordsAction;
  private final String lateRecordsDirPathTemplate;
  private final DataFormat dataFormat;
  private final CsvMode csvFileFormat;
  private final CsvHeader csvHeader;
  private final boolean csvReplaceNewLines;
  private final JsonMode jsonMode;
  private final String textFieldPath;
  private final boolean textEmptyLineIfNull;
  private String charset;
  private final String avroSchema;

  public HdfsTarget(String hdfsUri, String hdfsUser, boolean hdfsKerberos,
      String hadoopConfDir, Map<String, String> hdfsConfigs, String uniquePrefix, String dirPathTemplate,
      String timeZoneID, String timeDriver, long maxRecordsPerFile, long maxFileSize, CompressionMode compression,
      String otherCompression, HdfsFileType fileType, String keyEl,
      HdfsSequenceFileCompressionType seqFileCompressionType, String lateRecordsLimit,
      LateRecordsAction lateRecordsAction, String lateRecordsDirPathTemplate, DataFormat dataFormat, String charset,
      CsvMode csvFileFormat, CsvHeader csvHeader, boolean csvReplaceNewLines, JsonMode jsonMode, String textFieldPath,
      boolean textEmptyLineIfNull, String avroSchema) {
    this.hdfsUri = hdfsUri;
    this.hdfsUser = hdfsUser;
    this.hdfsKerberos = hdfsKerberos;
    this.hadoopConfDir = hadoopConfDir;
    this.hdfsConfigs = hdfsConfigs;
    this.uniquePrefix = uniquePrefix;
    this.dirPathTemplate = dirPathTemplate;
    this.timeZoneID = timeZoneID;
    this.timeDriver = timeDriver;
    this.maxRecordsPerFile = maxRecordsPerFile;
    this.maxFileSizeMBs = maxFileSize;
    this.compression = compression;
    this.otherCompression = otherCompression;
    this.fileType = fileType;
    this.keyEl = keyEl;
    this.seqFileCompressionType = seqFileCompressionType;
    this.lateRecordsLimit = lateRecordsLimit;
    this.lateRecordsAction = lateRecordsAction;
    this.lateRecordsDirPathTemplate = lateRecordsDirPathTemplate;
    this.dataFormat = dataFormat;
    this.csvFileFormat = csvFileFormat;
    this.csvHeader = csvHeader;
    this.csvReplaceNewLines = csvReplaceNewLines;
    this.jsonMode = jsonMode;
    this.textFieldPath = textFieldPath;
    this.textEmptyLineIfNull = textEmptyLineIfNull;
    this.charset = charset;
    this.avroSchema = avroSchema;
  }

  private Configuration hdfsConfiguration;
  private UserGroupInformation loginUgi;
  private long lateRecordsLimitSecs;
  private ActiveRecordWriters currentWriters;
  private ActiveRecordWriters lateWriters;
  private DataGeneratorFactory generatorFactory;
  private ELEval timeDriverElEval;
  private ELEval lateRecordsLimitEvaluator;
  private Date batchTime;
  private CompressionCodec compressionCodec;

  @Override
  protected List<ConfigIssue> init() {
    List<ConfigIssue> issues = super.init();
    boolean validHadoopDir = false;
    if (validateHadoopFS(issues)) {
      validHadoopDir = validateHadoopDir("dirPathTemplate", dirPathTemplate, issues);
      if (lateRecordsDirPathTemplate != null && !lateRecordsDirPathTemplate.isEmpty()) {
        validHadoopDir &= validateHadoopDir("lateRecordsDirPathTemplate", lateRecordsDirPathTemplate, issues);
      }
    }
    try {
      lateRecordsLimitEvaluator = getContext().createELEval("lateRecordsLimit");
      getContext().parseEL(lateRecordsLimit);
      lateRecordsLimitSecs = lateRecordsLimitEvaluator.eval(getContext().createELVars(),
        lateRecordsLimit, Long.class);
      if (lateRecordsLimitSecs <= 0) {
        issues.add(getContext().createConfigIssue(Groups.LATE_RECORDS.name(), "lateRecordsLimit", Errors.HADOOPFS_10));
      }
    } catch (Exception ex) {
      issues.add(getContext().createConfigIssue(Groups.LATE_RECORDS.name(), "lateRecordsLimit", Errors.HADOOPFS_06,
                                                lateRecordsLimit, ex.toString(), ex));
    }
    if (maxFileSizeMBs < 0) {
      issues.add(getContext().createConfigIssue(Groups.LATE_RECORDS.name(), "maxFileSize", Errors.HADOOPFS_08));
    }

    if (maxRecordsPerFile < 0) {
      issues.add(getContext().createConfigIssue(Groups.LATE_RECORDS.name(), "maxRecordsPerFile", Errors.HADOOPFS_09));
    }

    if (uniquePrefix == null) {
      uniquePrefix = "";
    }

    validateDataFormat(issues);
    generatorFactory = createDataGeneratorFactory();

    SequenceFile.CompressionType compressionType = (seqFileCompressionType != null)
                                                   ? seqFileCompressionType.getType() : null;

    try {
      switch (compression) {
        case OTHER:
          try {
            Class klass = Thread.currentThread().getContextClassLoader().loadClass(otherCompression);
            if (CompressionCodec.class.isAssignableFrom(klass)) {
              compressionCodec = ((Class<? extends CompressionCodec> ) klass).newInstance();
            } else {
              throw new StageException(Errors.HADOOPFS_04, otherCompression);
            }
          } catch (Exception ex1) {
            throw new StageException(Errors.HADOOPFS_05, otherCompression, ex1.toString(), ex1);
          }
          break;
        case NONE:
          break;
        default:
          compressionCodec = compression.getCodec().newInstance();
          break;
      }
      if (compressionCodec != null) {
        if (compressionCodec instanceof Configurable) {
          ((Configurable) compressionCodec).setConf(hdfsConfiguration);
        }
      }
      if(validHadoopDir) {
        RecordWriterManager mgr = new RecordWriterManager(new URI(hdfsUri), hdfsConfiguration, uniquePrefix,
          dirPathTemplate, TimeZone.getTimeZone(timeZoneID), lateRecordsLimitSecs, maxFileSizeMBs * MEGA_BYTE,
          maxRecordsPerFile, fileType, compressionCodec, compressionType, keyEl, generatorFactory, getContext(),
          "dirPathTemplate");

        if (mgr.validateDirTemplate(Groups.OUTPUT_FILES.name(), "dirPathTemplate", issues)) {
          currentWriters = new ActiveRecordWriters(mgr);
        }
      }
    } catch (Exception ex) {
      LOG.info("Validation Error: " + Errors.HADOOPFS_11.getMessage(), ex.toString(), ex);
      issues.add(getContext().createConfigIssue(Groups.OUTPUT_FILES.name(), null, Errors.HADOOPFS_11, ex.toString(),
                                                ex));
    }

    if (lateRecordsDirPathTemplate != null && !lateRecordsDirPathTemplate.isEmpty()) {
      if(validHadoopDir) {
        try {
          RecordWriterManager mgr = new RecordWriterManager(new URI(hdfsUri), hdfsConfiguration, uniquePrefix,
            lateRecordsDirPathTemplate, TimeZone.getTimeZone(timeZoneID), lateRecordsLimitSecs,
            maxFileSizeMBs * MEGA_BYTE, maxRecordsPerFile, fileType, compressionCodec, compressionType, keyEl,
            generatorFactory, getContext(), "lateRecordsDirPathTemplate");

          if (mgr.validateDirTemplate(Groups.OUTPUT_FILES.name(), "lateRecordsDirPathTemplate", issues)) {
            lateWriters = new ActiveRecordWriters(mgr);
          }
        } catch (Exception ex) {
          issues.add(getContext().createConfigIssue(Groups.LATE_RECORDS.name(), null, Errors.HADOOPFS_17,
                                                    ex.toString(), ex));
        }
      }
    }

    timeDriverElEval = getContext().createELEval("timeDriver");
    try {
      ELVars variables = getContext().createELVars();
      RecordEL.setRecordInContext(variables, getContext().createRecord("validationConfigs"));
      TimeNowEL.setTimeNowInContext(variables, new Date());
      getContext().parseEL(timeDriver);
      timeDriverElEval.eval(variables, timeDriver, Date.class);
    } catch (ELEvalException ex) {
      issues.add(getContext().createConfigIssue(Groups.OUTPUT_FILES.name(), "timeDriver", Errors.HADOOPFS_19,
                                                ex.toString(), ex));
    }

    if (issues.isEmpty()) {
      try {
        FileSystem fs = getFileSystemForInitDestroy();
        getCurrentWriters().commitOldFiles(fs);
        if (getLateWriters() != null) {
          getLateWriters().commitOldFiles(fs);
        }
      } catch (Exception ex) {
        issues.add(getContext().createConfigIssue(null, null, Errors.HADOOPFS_23, ex.toString(), ex));
      }
      toHdfsRecordsCounter = getContext().createCounter("toHdfsRecords");
      toHdfsRecordsMeter = getContext().createMeter("toHdfsRecords");
      lateRecordsCounter = getContext().createCounter("lateRecords");
      lateRecordsMeter = getContext().createMeter("lateRecords");
    }
    return issues;
  }

  Configuration getHadoopConfiguration(List<ConfigIssue> issues) {
    Configuration conf = new Configuration();
    conf.setClass("fs.file.impl", RawLocalFileSystem.class, FileSystem.class);
    if (hdfsKerberos) {
      conf.set(CommonConfigurationKeys.HADOOP_SECURITY_AUTHENTICATION,
               UserGroupInformation.AuthenticationMethod.KERBEROS.name());
      try {
        conf.set(DFSConfigKeys.DFS_NAMENODE_USER_NAME_KEY, "hdfs/_HOST@" + KerberosUtil.getDefaultRealm());
      } catch (Exception ex) {
        if (!hdfsConfigs.containsKey(DFSConfigKeys.DFS_NAMENODE_USER_NAME_KEY)) {
          issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), null, Errors.HADOOPFS_28,
                                                    ex.toString()));
        }
      }
    }
    if (hadoopConfDir != null && !hadoopConfDir.isEmpty()) {
      File hadoopConfigDir = new File(hadoopConfDir);
      if(getContext().isClusterMode() && hadoopConfigDir.isAbsolute()) {
        //Do not allow absolute hadoop config directory in cluster mode
        issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), "hadoopConfDir", Errors.HADOOPFS_45,
          hadoopConfDir));
      } else {
        if (!hadoopConfigDir.isAbsolute()) {
          hadoopConfigDir = new File(getContext().getResourcesDirectory(), hadoopConfDir).getAbsoluteFile();
        }
        if (!hadoopConfigDir.exists()) {
          issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), "hadoopConfDir", Errors.HADOOPFS_25,
            hadoopConfigDir.getPath()));
        } else if (!hadoopConfigDir.isDirectory()) {
          issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), "hadoopConfDir", Errors.HADOOPFS_26,
            hadoopConfigDir.getPath()));
        } else {
          File coreSite = new File(hadoopConfigDir, "core-site.xml");
          if (coreSite.exists()) {
            if (!coreSite.isFile()) {
              issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), "hadoopConfDir", Errors.HADOOPFS_27,
                coreSite.getPath()));
            }
            conf.addResource(new Path(coreSite.getAbsolutePath()));
          }
          File hdfsSite = new File(hadoopConfigDir, "hdfs-site.xml");
          if (hdfsSite.exists()) {
            if (!hdfsSite.isFile()) {
              issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), "hadoopConfDir", Errors.HADOOPFS_27,
                hdfsSite.getPath()));
            }
            conf.addResource(new Path(hdfsSite.getAbsolutePath()));
          }
        }
      }
    }
    for (Map.Entry<String, String> config : hdfsConfigs.entrySet()) {
      conf.set(config.getKey(), config.getValue());
    }
    return conf;
  }

  private boolean validateHadoopFS(List<ConfigIssue> issues) {
    boolean validHapoopFsUri = true;
    if (hdfsUri.contains("://")) {
      try {
        new URI(hdfsUri);
      } catch (Exception ex) {
        issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), null, Errors.HADOOPFS_22, hdfsUri,
          ex.toString(), ex));
        validHapoopFsUri = false;
      }
    } else {
      issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), "hdfsUri", Errors.HADOOPFS_18, hdfsUri));
      validHapoopFsUri = false;
    }

    StringBuilder logMessage = new StringBuilder();
    try {
      hdfsConfiguration = getHadoopConfiguration(issues);

      hdfsConfiguration.set(CommonConfigurationKeys.FS_DEFAULT_NAME_KEY, hdfsUri);

      // forcing UGI to initialize with the security settings from the stage
      UserGroupInformation.setConfiguration(hdfsConfiguration);
      Subject subject = Subject.getSubject(AccessController.getContext());
      if (UserGroupInformation.isSecurityEnabled()) {
        loginUgi = UserGroupInformation.getUGIFromSubject(subject);
      } else {
        UserGroupInformation.loginUserFromSubject(subject);
        loginUgi = UserGroupInformation.getLoginUser();
      }
      LOG.info("Subject = {}, Principals = {}, Login UGI = {}", subject,
        subject == null ? "null" : subject.getPrincipals(), loginUgi);
      if (hdfsKerberos) {
        logMessage.append("Using Kerberos");
        if (loginUgi.getAuthenticationMethod() != UserGroupInformation.AuthenticationMethod.KERBEROS) {
          issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), "hdfsKerberos", Errors.HADOOPFS_00,
                                                    loginUgi.getAuthenticationMethod(),
                                                    UserGroupInformation.AuthenticationMethod.KERBEROS));
        }
      } else {
        logMessage.append("Using Simple");
        hdfsConfiguration.set(CommonConfigurationKeys.HADOOP_SECURITY_AUTHENTICATION,
                              UserGroupInformation.AuthenticationMethod.SIMPLE.name());
      }
      if (validHapoopFsUri) {
        getUGI().doAs(new PrivilegedExceptionAction<Void>() {
          @Override
          public Void run() throws Exception {
            try (FileSystem fs = getFileSystemForInitDestroy()) { //to trigger the close
            }
            return null;
          }
        });
      }
    } catch (Exception ex) {
      LOG.info("Validation Error: " + Errors.HADOOPFS_01.getMessage(), hdfsUri, ex.toString(), ex);
      issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), null, Errors.HADOOPFS_01, hdfsUri,
        String.valueOf(ex), ex));
    }
    LOG.info("Authentication Config: " + logMessage);
    return validHapoopFsUri;
  }

  boolean validateHadoopDir(String configName, String dirPathTemplate, List<ConfigIssue> issues) {
    boolean ok;
    if (!dirPathTemplate.startsWith("/")) {
      issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), configName, Errors.HADOOPFS_40));
      ok = false;
    } else {
      int firstEL = dirPathTemplate.indexOf("$");
      if (firstEL > -1) {
        int lastDir = dirPathTemplate.lastIndexOf("/", firstEL);
        dirPathTemplate = dirPathTemplate.substring(0, lastDir);
      }
      dirPathTemplate = (dirPathTemplate.isEmpty()) ? "/" : dirPathTemplate;
      try {
        Path dir = new Path(dirPathTemplate);
        FileSystem fs = getFileSystemForInitDestroy();
        if (!fs.exists(dir)) {
          try {
            if (fs.mkdirs(dir)) {
              ok = true;
            } else {
              issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), configName, Errors.HADOOPFS_41));
              ok = false;
            }
          } catch (IOException ex) {
            issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), configName, Errors.HADOOPFS_42,
                                                      ex.toString()));
            ok = false;
          }
        } else {
          try {
            Path dummy = new Path(dir, "_sdc-dummy-" + UUID.randomUUID().toString());
            fs.create(dummy).close();
            fs.delete(dummy, false);
            ok = true;
          } catch (IOException ex) {
            issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), configName, Errors.HADOOPFS_43,
                                                      ex.toString()));
            ok = false;
          }
        }
      } catch (Exception ex) {
        LOG.info("Validation Error: " + Errors.HADOOPFS_44.getMessage(), ex.toString(), ex);
        issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), configName, Errors.HADOOPFS_44,
                                                  ex.toString()));
        ok = false;
      }
    }
    return ok;
  }


  private UserGroupInformation getUGI() {
    return (hdfsUser.isEmpty()) ? loginUgi : UserGroupInformation.createProxyUser(hdfsUser, loginUgi);
  }

  private FileSystem getFileSystemForInitDestroy() throws Exception {
    try {
      return getUGI().doAs(new PrivilegedExceptionAction<FileSystem>() {
        @Override
        public FileSystem run() throws Exception {
          return FileSystem.get(new URI(hdfsUri), hdfsConfiguration);
        }
      });
    } catch (IOException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof Exception) {
        throw (Exception)cause;
      }
      throw ex;
    }
  }

  private static StageException throwStageException(Exception e) {
    if (e instanceof RuntimeException) {
      Throwable cause = e.getCause();
      if (cause != null) {
        return new StageException(Errors.HADOOPFS_13, String.valueOf(cause), cause);
      }
    }
    return new StageException(Errors.HADOOPFS_13, String.valueOf(e), e);
  }

  Configuration getHdfsConfiguration() {
    return hdfsConfiguration;
  }

  CompressionCodec getCompressionCodec() throws StageException {
    return compressionCodec;
  }

  // for testing only
  long getLateRecordLimitSecs() {
    return lateRecordsLimitSecs;
  }

  private void validateDataFormat(List<ConfigIssue> issues) {
    switch (dataFormat) {
      case TEXT:
      case JSON:
      case DELIMITED:
      case SDC_JSON:
      case AVRO:
        break;
      default:
        issues.add(getContext().createConfigIssue(Groups.OUTPUT_FILES.name(), "dataFormat", Errors.HADOOPFS_16,
                                                  dataFormat));
    }
  }

  private DataGeneratorFactory createDataGeneratorFactory() {
    DataGeneratorFactoryBuilder builder = new DataGeneratorFactoryBuilder(getContext(),
      dataFormat.getGeneratorFormat());
    if(charset == null || charset.trim().isEmpty()) {
      charset = "UTF-8";
    }
    builder.setCharset(Charset.forName(charset));
    switch(dataFormat) {
      case JSON:
        builder.setMode(jsonMode);
        break;
      case DELIMITED:
        builder.setMode(csvFileFormat);
        builder.setMode(csvHeader);
        builder.setConfig(DelimitedDataGeneratorFactory.REPLACE_NEWLINES_KEY, csvReplaceNewLines);
        break;
      case TEXT:
        builder.setConfig(TextDataGeneratorFactory.FIELD_PATH_KEY, textFieldPath);
        builder.setConfig(TextDataGeneratorFactory.EMPTY_LINE_IF_NULL_KEY, textEmptyLineIfNull);
        break;
      case SDC_JSON:
        break;
      case AVRO:
        builder.setConfig(AvroDataGeneratorFactory.SCHEMA_KEY, avroSchema);
        break;
      case XML:
      default:
        throw new IllegalStateException("It should not happen");
    }
    return builder.build();
  }

  @Override
  public void destroy() {
    LOG.info("Destroy");
    try {
      if (currentWriters != null) {
        currentWriters.closeAll();
      }
      if (lateWriters != null) {
        lateWriters.closeAll();
      }
      if (loginUgi != null) {
        getFileSystemForInitDestroy().close();
      }
    } catch (Exception ex) {
      LOG.warn("Error while closing HDFS FileSystem URI='{}': {}", hdfsUri, ex.toString(), ex);
    }
    super.destroy();
  }

  @Override
  public void write(final Batch batch) throws StageException {
    setBatchTime();
    try {
      getUGI().doAs(new PrivilegedExceptionAction<Void>() {
        @Override
        public Void run() throws Exception {
          getCurrentWriters().purge();
          if (getLateWriters() != null) {
            getLateWriters().purge();
          }
          Iterator<Record> it = batch.getRecords();
          if (it.hasNext()) {
            while (it.hasNext()) {
              Record record = it.next();
              try {
                write(record);
              } catch (OnRecordErrorException ex) {
                switch (getContext().getOnErrorRecord()) {
                  case DISCARD:
                    break;
                  case TO_ERROR:
                    getContext().toError(record, ex);
                    break;
                  case STOP_PIPELINE:
                    throw ex;
                  default:
                    throw new IllegalStateException(Utils.format("It should never happen. OnError '{}'",
                                                                 getContext().getOnErrorRecord(), ex));
                }
              }
            }
            getCurrentWriters().flushAll();
          } else {
            emptyBatch();
          }
          return null;
        }
      });
    } catch (Exception ex) {
      throw throwStageException(ex);
    }
  }

  // we use the emptyBatch() method call to close open files when the late window closes even if there is no more
  // new data.
  protected void emptyBatch() throws StageException {
    setBatchTime();
    try {
      getUGI().doAs(new PrivilegedExceptionAction<Void>() {
        @Override
        public Void run() throws Exception {
          getCurrentWriters().purge();
          if (getLateWriters() != null) {
            getLateWriters().purge();
          }
          return null;
        }
      });
    } catch (Exception ex) {
      throw throwStageException(ex);
    }
  }

  //visible for testing.
  Date setBatchTime() {
    batchTime = new Date();
    return batchTime;
  }

  protected Date getBatchTime() {
    return batchTime;
  }

  protected ActiveRecordWriters getCurrentWriters() {
    return currentWriters;
  }

  protected ActiveRecordWriters getLateWriters() {
    return lateWriters;
  }

  protected Date getRecordTime(Record record) throws ELEvalException {
    ELVars variables = getContext().createELVars();
    TimeNowEL.setTimeNowInContext(variables, getBatchTime());
    RecordEL.setRecordInContext(variables, record);
    return timeDriverElEval.eval(variables, timeDriver, Date.class);
  }

  private Counter toHdfsRecordsCounter;
  private Meter toHdfsRecordsMeter;
  private Counter lateRecordsCounter;
  private Meter lateRecordsMeter;

  protected void write(Record record) throws StageException {
    try {
      Date recordTime = getRecordTime(record);
      RecordWriter writer = getCurrentWriters().get(getBatchTime(), recordTime, record);
      if (writer != null) {
        toHdfsRecordsCounter.inc();
        toHdfsRecordsMeter.mark();
        writer.write(record);
        getCurrentWriters().release(writer);
      } else {
        lateRecordsCounter.inc();
        lateRecordsMeter.mark();
        switch (lateRecordsAction) {
          case SEND_TO_ERROR:
            getContext().toError(record, Errors.HADOOPFS_12, record.getHeader().getSourceId());
            break;
          case SEND_TO_LATE_RECORDS_FILE:
            RecordWriter lateWriter = getLateWriters().get(getBatchTime(), getBatchTime(), record);
            lateWriter.write(record);
            getLateWriters().release(lateWriter);
            break;
          default:
            throw new RuntimeException("It should never happen");
        }
      }
    } catch (IOException ex) {
      throw new StageException(Errors.HADOOPFS_14, ex.toString(), ex);
    } catch (StageException ex) {
      throw new OnRecordErrorException(ex.getErrorCode(), ex.getParams()); // params includes exception
    }
  }

}
