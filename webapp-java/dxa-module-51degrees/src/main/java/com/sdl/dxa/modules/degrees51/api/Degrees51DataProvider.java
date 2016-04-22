package com.sdl.dxa.modules.degrees51.api;

import com.sdl.webapp.common.api.WebRequestContext;
import com.sdl.webapp.common.exceptions.DxaException;
import fiftyone.mobile.detection.AutoUpdate;
import fiftyone.mobile.detection.AutoUpdateStatus;
import fiftyone.mobile.detection.Match;
import fiftyone.mobile.detection.Provider;
import fiftyone.mobile.detection.entities.stream.Dataset;
import fiftyone.mobile.detection.factories.StreamFactory;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static org.apache.commons.io.FileUtils.readFileToByteArray;
import static org.joda.time.DateTime.now;

@Component
@Slf4j
public class Degrees51DataProvider {

    private static final Semaphore liteFileAccess = new Semaphore(1, true);

    private static final Map<String, String> fileNamesByLicense = new HashMap<>();

    private static final Map<String, Date> fileNextUpdatesByNames = new HashMap<>();

    private static final Map<String, Provider> dataProvidersByNames = new HashMap<>();

    @Autowired
    private WebRequestContext webRequestContext;

    @Value("${dxa.modules.51degrees.file.lite.url}")
    private String degrees51DataLiteUrl;

    @Value("${dxa.modules.51degrees.file.lite.location}")
    private String liteFileLocation;

    @Value("${dxa.modules.51degrees.file.locationPattern}")
    private String dataFileLocationPattern;

    @Value("${dxa.modules.51degrees.file.timeout.mins}")
    private long fileUpdateTimeoutMinutes;

    @Value("${dxa.modules.51degrees.license:#{null}}")
    private String preConfiguredLicenseKey;

    public Match match(String userAgent) {
        try {
            String fileName = getCurrentFileName();

            Provider provider = dataProvidersByNames.containsKey(fileName) ? dataProvidersByNames.get(fileName) :
                    memorize(dataProvidersByNames, fileName, new Provider(StreamFactory.create(readFileToByteArray(new File(fileName))), 32));

            return provider.match(userAgent);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private String getCurrentFileName() {
        String fileName;

        String licenseKey = webRequestContext.getLocalization().getConfiguration("51degrees.licenseKey");
        if (licenseKey != null && (fileName = updateAndGiveFileName(licenseKey)) != null) {
            log.debug("Using licenseKey key configured in CM");
            return fileName;
        }

        if (preConfiguredLicenseKey != null && (fileName = updateAndGiveFileName(preConfiguredLicenseKey)) != null) {
            log.debug("Using licenseKey key configured in properties");
            return fileName;
        }

        fileName = updateLiteAndGiveFileName();
        log.debug("No licenseKey key found for 51degrees module, fallback to Lite");
        return fileName;
    }

    @PostConstruct
    private void onAppStart() {

        log.debug("Check if the 51degrees licenseKey is in properties");
        if (preConfiguredLicenseKey != null) {
            log.debug("The licenseKey key for 51degrees found in properties, pre-loading data file");
            updateAndGiveFileName(preConfiguredLicenseKey);
        } else {
            log.debug("The licenseKey key for 51degrees is not in properties, pre-loading Lite file");
//            updateLiteAndGiveFileName();
        }
    }

    @SneakyThrows(IOException.class)
    private String updateAndGiveFileName(String licenseKey) {
        String fileName = getDataFileName(licenseKey);

        if (!isUpdateNeeded(fileName)) {
            log.info("51degrees data file is up-to-date, update is not needed");
            return fileName;
        }

        try {
            AutoUpdateStatus status = AutoUpdate.update(licenseKey, fileName);
            switch (status) {
                case AUTO_UPDATE_SUCCESS:
                    log.info("API: 51degrees data file has been updated");
                    getAndSetNextUpdate(fileName);
                    return fileName;
                case AUTO_UPDATE_NOT_NEEDED:
                    log.info("API: 51degrees data file is up-to-date, updateDataFile is not needed");
                    return fileName;
                default:
                    log.error("There was a problem updating the data file: {}", status);
                    throw new DxaException("There was a problem updating the data file: " + status);
            }
        } catch (Exception e) {
            log.error("Exception while updating 51degrees data file.", e);
            return null;
        }
    }


    @SneakyThrows({NoSuchAlgorithmException.class, UnsupportedEncodingException.class})
    private String getDataFileName(String licenseKey) {
        String fileName = fileNamesByLicense.get(licenseKey);
        if (fileName != null) {
            return fileName;
        }

        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(licenseKey.getBytes("UTF-8"));
        return memorize(fileNamesByLicense, licenseKey, String.format(dataFileLocationPattern,
                String.format("%032x", new BigInteger(1, md.digest()))));
    }

//    //    @Scheduled(cron = "0 0 0/6 * * ?")
//    private void updateDataFile() {
//        if (isNullOrEmpty(loadLicenseKey())) {
//            log.warn("Update of 51degrees data file failed, because licenseKey key is not set, fallback to Lite data file");
//            updateLiteAndGiveFileName();
//            return;
//        }
//
//        final ExecutorService executorService = Executors.newFixedThreadPool(2);
//        executorService.execute(new Runnable() {
//            @Override
//            public void run() {
//                FutureTask<AutoUpdateStatus> futureTask = new FutureTask<>(new Callable<AutoUpdateStatus>() {
//                    @Override
//                    public AutoUpdateStatus call() throws Exception {
//                        return null;
////                        return AutoUpdate.updateDataFile(loadLicenseKey(), dataFileLocationPattern);
//                    }
//                });
//
//                log.info("Update of 51degrees data file {} started...", dataFileLocationPattern);
//
//                AutoUpdateStatus status;
//                try {
//                    executorService.execute(futureTask);
//                    status = futureTask.get(fileUpdateTimeoutMinutes, TimeUnit.MINUTES);
//                    executorService.shutdown();
//
//
//                } catch (Exception e) {
//                    log.error("Exception while updating 51degrees data file.", e);
//                    updateLiteAndGiveFileName();
//                }
//
////                fileAccess.release();
//                log.info("Update of 51degrees data file {} completed...", dataFileLocationPattern);
//            }
//        });
//    }


    @Scheduled(cron = "0 0 4 ? * SAT")
    private void updateLiteScheduled() {
        updateLiteAndGiveFileName();
    }

    private String updateLiteAndGiveFileName() {
        File liteFile = new File(liteFileLocation);
        try {
            if (!isUpdateNeeded(liteFileLocation)) {
                log.debug("51degrees lite file doesn't need to be updated");
                return liteFileLocation;
            }

            log.info("51degrees lite file needs to be updated");
            liteFileAccess.acquire();
            if (!deleteDataFile(liteFileLocation)) {
                throw new IOException("Could not delete Lite file, (access denied?)");
            }
            FileUtils.copyURLToFile(new URL(degrees51DataLiteUrl), liteFile);
            liteFileAccess.release();

            log.info("51degrees lite file is updated");
            getAndSetNextUpdate(liteFileLocation);
        } catch (IOException | InterruptedException e) {
            log.error("Exception while updating the 51degrees lite file, deleting", e);
            FileUtils.deleteQuietly(liteFile);
            return null;
        }
        return liteFileLocation;
    }

    private boolean isUpdateNeeded(String fileName) throws IOException {
        Date nextUpdateDate;
        if (fileNextUpdatesByNames.containsKey(fileName)) {
            nextUpdateDate = fileNextUpdatesByNames.get(fileName);
        } else {
            File file = new File(fileName);
            if (!file.exists()) {
                return true;
            }

            nextUpdateDate = getAndSetNextUpdate(fileName);
        }
        return now().isAfter(new DateTime(nextUpdateDate));
    }

    private Date getAndSetNextUpdate(String fileName) throws IOException {
        try (Dataset dataset = StreamFactory.create(readFileToByteArray(new File(fileName)))) {
            Date nextUpdate = dataset.nextUpdate;
            log.trace("Next expected updated for {} is {}", fileName, nextUpdate);
            return memorize(fileNextUpdatesByNames, fileName, nextUpdate);
        }
    }

    private boolean deleteDataFile(String fileName) throws IOException {
        if (dataProvidersByNames.containsKey(fileName)) {
            Provider provider = dataProvidersByNames.get(fileName);
            dataProvidersByNames.remove(fileName);
            provider.dataSet.close();
        }
        try {
            File file = new File(fileName);
            liteFileAccess.acquire();
            return !file.exists() || FileUtils.deleteQuietly(file);
        } catch (InterruptedException e) {
            log.error("Exception deleting the file {}", fileName, e);
        } finally {
            liteFileAccess.release();
        }
        return false;
    }

    private <T> T memorize(Map<String, T> map, String key, T value) {
        map.put(key, value);
        return value;
    }
}
