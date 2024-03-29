package org.openmrs.module.esaudefeatures.web.controller;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.r4.model.Bundle;
import org.openmrs.Patient;
import org.openmrs.api.AdministrationService;
import org.openmrs.module.esaudefeatures.web.FhirSearchDelegate;
import org.openmrs.module.esaudefeatures.web.RemoteOpenmrsSearchDelegate;
import org.openmrs.module.esaudefeatures.web.exception.FhirResourceSearchException;
import org.openmrs.module.esaudefeatures.web.exception.RemoteImportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import javax.servlet.http.HttpServletResponse;

import static org.openmrs.module.esaudefeatures.EsaudeFeaturesConstants.REMOTE_SERVER_TYPE_GP;

/**
 * @uthor Willa Mhawila<a.mhawila@gmail.com> on 11/22/21.
 */
@Controller("esaudefeatures.fhirSearchController")
public class FhirSearchController {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(FhirSearchController.class);
	
	private FhirSearchDelegate fhirSearchDelegate;
	
	private RemoteOpenmrsSearchDelegate openmrsSearchDelegate;
	
	private IParser parser = FhirContext.forR4().newJsonParser();
	
	private AdministrationService adminService;
	
	@Autowired
	public void setAdminService(AdministrationService adminService) {
		this.adminService = adminService;
	}
	
	@Autowired
	public void setFhirSearchDelegate(FhirSearchDelegate fhirSearchDelegate) {
		this.fhirSearchDelegate = fhirSearchDelegate;
	}
	
	@Autowired
	public void setOpenmrsSearchDelegate(RemoteOpenmrsSearchDelegate openmrsSearchDelegate) {
		this.openmrsSearchDelegate = openmrsSearchDelegate;
	}
	
	@ResponseBody
	@RequestMapping(method = RequestMethod.GET, value = "/module/esaudefeatures/fhirRemotePatients.json", produces = {
	        "application/json", "application/json+fhir" })
	public String searchFhirServerForPatient(@RequestParam("text") String searchText,
	        @RequestParam("matchMode") String matchMode) throws Exception {
		String message = "Could not fetch data, Global property %s not set";
		String fhirProvider = adminService.getGlobalProperty(REMOTE_SERVER_TYPE_GP);
		if (StringUtils.isEmpty(fhirProvider)) {
			LOGGER.warn(String.format(message, REMOTE_SERVER_TYPE_GP));
			throw new FhirResourceSearchException(message, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
		Bundle bundle = fhirSearchDelegate.searchForPatients(searchText, fhirProvider, matchMode);
		return parser.encodeResourceToString(bundle);
	}
	
	@ResponseStatus(HttpStatus.CREATED)
	@RequestMapping(method = RequestMethod.POST, value = "/module/esaudefeatures/fhirPatient.json", produces = { "application/json" })
	@ResponseBody
	public String importPatient(@RequestParam("patientId") String opencrPatientId) {
		String message = "Could not fetch data, Global property %s not set";
		String fhirProvider = adminService.getGlobalProperty(REMOTE_SERVER_TYPE_GP);
		if (StringUtils.isEmpty(fhirProvider)) {
			LOGGER.warn(String.format(message, REMOTE_SERVER_TYPE_GP));
			throw new FhirResourceSearchException(message, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
		try {
			Patient patient = fhirSearchDelegate.importPatient(opencrPatientId, fhirProvider);
			
			try {
				openmrsSearchDelegate.importRelationshipsForPerson(patient.getPerson());
			}
			catch (Exception e) {
				// TODO: Tell the user that we failed. (Challenging since we don't fail the patient import based on status of relationship import)
				LOGGER.warn("Could not import relationships for patient with uuid {}", patient.getUuid(), e);
			}
			return patient.getPatientId().toString();
		}
		catch (Exception e) {
			LOGGER.error("An error occured while importing patient from opencr with fhirId {}", opencrPatientId, e);
			throw new RemoteImportException(e.getMessage(), e, HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
}
