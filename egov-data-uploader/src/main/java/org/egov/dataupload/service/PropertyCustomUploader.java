package org.egov.dataupload.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.egov.common.contract.request.RequestInfo;
import org.egov.dataupload.model.UploadJob;
import org.egov.dataupload.model.UploadJob.StatusEnum;
import org.egov.dataupload.model.UploaderRequest;
import org.egov.dataupload.property.PropertyFileReader;
import org.egov.dataupload.property.models.Property;
import org.egov.dataupload.repository.UploadRegistryRepository;
import org.egov.dataupload.utils.DataUploadUtils;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PropertyCustomUploader {

	@Autowired
	private DataUploadService dataUploadService;

	@Autowired
	private PropertyFileReader propFileReader;

	@Autowired
	private ObjectMapper mapper;

	@Autowired
	private UploadRegistryRepository uploadRegistryRepository;

	@Autowired
	private DataUploadUtils uploadUtils;

	@Value("${property.host}")
	private String propertyHost;

	@Value("${property.create}")
	private String propertyCreate;

	@Value("${internal.file.folder.path}")
	private String internalFolderPath;
	
	private String responseString = "Response";

	public void uploadPropertyData(UploaderRequest uploaderRequest) {

		RequestInfo requestInfo = uploaderRequest.getRequestInfo();
		UploadJob job = uploaderRequest.getUploadJobs().get(0);
		String loc = job.getLocalFilePath();
		Map<String, Property> map = null;

		job.setStartTime(System.currentTimeMillis());
		job.setStatus(StatusEnum.INPROGRESS);
		uploadRegistryRepository.updateJob(job);

		/*
		 * Resulting map from 'propFileReader.parseExcel' is a linkedhashmap to preserve
		 * the order of insertion so that results can be written back in to excel with
		 * ease
		 */
		try {
			map = propFileReader.parseExcel(loc);
		} catch (EncryptedDocumentException | InvalidFormatException | IOException e) {

			log.error(" exception occured while parsing the excel : ", e);

			job.setEndTime(System.currentTimeMillis());
			job.setStatus(StatusEnum.FAILED);
			job.setReasonForFailure("parsing of the excel failed");
			uploadRegistryRepository.updateJob(job);
			uploadUtils.clearInternalDirectory();
			throw new CustomException("Exception Occured while parsing the excel", e.getMessage());
		}

		List<String> responses = new ArrayList<>();
		int failCnt = 0;
		int sucCnt = 0;

		for (Entry<String, Property> entry : map.entrySet()) {

			String failureMessage = null;

			Object response = dataUploadService.hitApi(getRequestForPost(entry.getValue(), requestInfo),
					getUrlForPost());

			if (null == response) {
				failureMessage = "Module API failed with empty body in response";
				failCnt++;
			} else {
				if (response instanceof String) {
					failureMessage = response.toString();
					failCnt++;
				} else {
					sucCnt++;
				}
				responses.add(failureMessage);
			}
		}

		// the responses will be taken in the same order as written in excel
		writeToExcel(job, responses);

		// Upload to s3 and set result file path to job
		String s3Id = null;
		try {
			s3Id = dataUploadService.getFileStoreId(job.getTenantId(), job.getModuleName(), job.getResponseFilePath());
		} catch (RestClientException | JsonProcessingException e) {

			log.error(" upload of the excel sheet failed : ", e);

			job.setEndTime(System.currentTimeMillis());
			job.setStatus(StatusEnum.FAILED);
			job.setReasonForFailure("upload of the excel sheet failed");
			uploadRegistryRepository.updateJob(job);
			uploadUtils.clearInternalDirectory();
			throw new CustomException("upload of the excel sheet failed", e.getMessage());
		}

		job.setResponseFilePath(s3Id);
		job.setEndTime(System.currentTimeMillis());
		job.setFailedRows(failCnt);
		job.setSuccessfulRows(sucCnt);
		job.setStatus(StatusEnum.COMPLETED);
		job.setTotalRows(map.size());

		uploadRegistryRepository.updateJob(job);
		uploadUtils.clearInternalDirectory();

		log.info(" the id of s3 data : " + s3Id);
	}

	/**
	 * method to write the responses from the API in to the excel sheet
	 * 
	 * 1. read the file from local storage
	 * 
	 * 2. convert the i/p stream to workbook
	 * 
	 * 3. get the property sheet from the book
	 * 
	 * 4. read the rows and write the responses in to them
	 * 
	 * 5. save the response excel and close the resources
	 * 
	 * @param fileLoc
	 * @param resopnses
	 */
	private void writeToExcel(UploadJob job, List<String> resopnses) {

		FileInputStream myxls = null;
		XSSFWorkbook propertyExcel = null;

		String resPath = responseString + "-" + job.getCode() + "-" + job.getRequestFileName();
		job.setResponseFilePath(resPath);

		try {

			myxls = new FileInputStream(job.getLocalFilePath());
			propertyExcel = new XSSFWorkbook(myxls);
			XSSFSheet propertySheet = propertyExcel.getSheetAt(0);
			Row firstRow = propertySheet.getRow(0);

			int fixedResNum = firstRow.getLastCellNum();
			Cell lastCell = firstRow.getCell(fixedResNum);

			if (null != lastCell)
				lastCell.setCellValue(responseString);
			else
				firstRow.createCell(fixedResNum).setCellValue(responseString);

			for (int i = 0; i < resopnses.size(); i++) {

				Row currRow = propertySheet.getRow(i + 1);
				Cell resCell = currRow.createCell(fixedResNum);
				String value = resopnses.get(i);
				resCell.setCellValue(value);
			}

			myxls.close();
			FileOutputStream outputFile = new FileOutputStream(new File(internalFolderPath + File.separator + resPath));
			propertyExcel.write(outputFile);
			outputFile.close();

			log.info(" file is successfully written");

		} catch (Exception e) {

			log.error(" Exception occured while writing in sheet : ", e);

			job.setEndTime(System.currentTimeMillis());
			job.setStatus(StatusEnum.FAILED);
			job.setReasonForFailure(" writing response in excel failed");
			uploadRegistryRepository.updateJob(job);
			uploadUtils.clearInternalDirectory();
			throw new CustomException("Exception occured while writing in sheet", e.getMessage());
		} finally {

			if (null != myxls)
				try {
					myxls.close();
				} catch (IOException e) {
					log.error("exception while closing resource in write file ");
				}

			if (null != propertyExcel)
				try {
					propertyExcel.close();
				} catch (IOException e) {
					log.error("exception while closing resource in write file ");
				}
		}
	}

	/**
	 * Creates a request Object(map) with RequestInfo and Properties
	 * 
	 * @param property
	 * @param requestInfo
	 * @return
	 */
	private String getRequestForPost(Property property, RequestInfo requestInfo) {

		Map<String, Object> reqMap = new HashMap<>();
		reqMap.put("RequestInfo", requestInfo);
		reqMap.put("Properties", Arrays.asList(property));

		String res = null;
		try {
			res = mapper.writeValueAsString(reqMap);
		} catch (JsonProcessingException e) {
			log.error(" Error occured while writing request map to string : ", e);
			throw new CustomException(" Error occured while writing request map to string : ", e.getMessage());
		}
		return res;
	}

	/**
	 * Method to provide the url for the API call
	 * 
	 */
	private String getUrlForPost() {
		return propertyHost.concat(propertyCreate);
	}
}
