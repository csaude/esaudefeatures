package org.openmrs.module.esaudefeatures.web.controller;

import org.openmrs.Patient;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.module.esaudefeatures.EsaudeFeaturesConstants;
import org.openmrs.module.esaudefeatures.ImportLogUtils;
import org.openmrs.module.esaudefeatures.web.RemoteOpenmrsSearchDelegate;
import org.openmrs.module.esaudefeatures.web.Utils;
import org.openmrs.module.esaudefeatures.web.exception.RemoteOpenmrsSearchException;
import org.openmrs.module.webservices.rest.SimpleObject;
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
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.HashMap;
import java.util.Map;

import static org.openmrs.module.esaudefeatures.EsaudeFeaturesConstants.DEFAULT_PATIENT_NAMES_MATCH_MODE_GP;
import static org.openmrs.module.esaudefeatures.EsaudeFeaturesConstants.FHIR_IDENTIFIER_SYSTEM_FOR_OPENMRS_UUID_GP;
import static org.openmrs.module.esaudefeatures.EsaudeFeaturesConstants.FHIR_IDENTIFIER_SYSTEM_TO_OPENMRS_IDENTIFIER_TYPE_UUID_MAPPINGS_GP;
import static org.openmrs.module.esaudefeatures.EsaudeFeaturesConstants.FHIR_REMOTE_SERVER_URL_GP;
import static org.openmrs.module.esaudefeatures.EsaudeFeaturesConstants.OPENMRS_REMOTE_SERVER_PASSWORD_GP;
import static org.openmrs.module.esaudefeatures.EsaudeFeaturesConstants.OPENMRS_REMOTE_SERVER_URL_GP;
import static org.openmrs.module.esaudefeatures.EsaudeFeaturesConstants.OPENMRS_REMOTE_SERVER_USERNAME_GP;
import static org.openmrs.module.esaudefeatures.EsaudeFeaturesConstants.REMOTE_SERVER_TYPE_GP;
import static org.openmrs.util.OpenmrsConstants.OPENMRS_VERSION_SHORT;

/**
 * @uthor Willa Mhawila<a.mhawila@gmail.com> on 8/5/21.
 */
@Controller("esaudefeatures.remotePatientsController")
public class OpenmrsSearchController {
	
	private AdministrationService adminService;
	
	private ImportLogUtils importLogUtils;
	
	private RemoteOpenmrsSearchDelegate delegate;
	
	private static final Logger LOGGER = LoggerFactory.getLogger(OpenmrsSearchController.class);
	
	public static final String ROOT_PATH = "/module/esaudefeatures/findRemotePatients.form";
	
	public static final String ALT_ROOT_PATH = "/module/esaudefeatures/findRemotePatients.htm";
	
	@Autowired
	public void setAdminService(AdministrationService adminService) {
		this.adminService = adminService;
	}
	
	@Autowired
	public void setImportLogUtils(ImportLogUtils importLogUtils) {
		this.importLogUtils = importLogUtils;
	}
	
	@Autowired
	public void setDelegate(RemoteOpenmrsSearchDelegate delegate) {
		this.delegate = delegate;
	}
	
	@RequestMapping(method = RequestMethod.GET, value = { ROOT_PATH, ALT_ROOT_PATH })
	public ModelAndView searchRemoteForm(ModelAndView modelAndView) {
		if (modelAndView == null) {
			modelAndView = new ModelAndView();
		}
		
		if (OPENMRS_VERSION_SHORT.startsWith("1")) {
			modelAndView.setViewName("module/esaudefeatures/remotePatients/findRemotePatients1x");
		} else {
			modelAndView.setViewName("module/esaudefeatures/remotePatients/findRemotePatients2x");
		}
		
		if (Context.getAuthenticatedUser() == null) {
			return modelAndView;
		}
		
		modelAndView.getModelMap().addAttribute("authenticatedUser", Context.getAuthenticatedUser());
		// Get all import logs
		modelAndView.getModelMap().addAttribute("importLogs", importLogUtils.getAllImportLogs());
		
		String serverType = adminService.getGlobalProperty(REMOTE_SERVER_TYPE_GP, "OPENMRS");
		if ("OPENMRS".equalsIgnoreCase(serverType)) {
			modelAndView.getModelMap().addAttribute("remoteServerUrl",
			    adminService.getGlobalProperty(OPENMRS_REMOTE_SERVER_URL_GP));
		} else {
			modelAndView.getModelMap().addAttribute("remoteServerUrl",
			    adminService.getGlobalProperty(FHIR_REMOTE_SERVER_URL_GP));
		}
		
		modelAndView.getModelMap().addAttribute("remoteServerType",
		    adminService.getGlobalProperty(REMOTE_SERVER_TYPE_GP, "OPENMRS"));
		
		modelAndView.getModelMap().addAttribute("fhirIdentifierSystemMappings",
		    adminService.getGlobalProperty(FHIR_IDENTIFIER_SYSTEM_TO_OPENMRS_IDENTIFIER_TYPE_UUID_MAPPINGS_GP));
		
		modelAndView.getModelMap().addAttribute("openmrsPersonUuidFhirSystemValue",
		    adminService.getGlobalProperty(FHIR_IDENTIFIER_SYSTEM_FOR_OPENMRS_UUID_GP));
		
		String defaultMatchMode = adminService.getGlobalProperty(DEFAULT_PATIENT_NAMES_MATCH_MODE_GP, "fuzzy");
		if ("exact".equalsIgnoreCase(defaultMatchMode)) {
			modelAndView.getModelMap().addAttribute("matchModeExact", "checked");
		} else {
			modelAndView.getModelMap().addAttribute("matchModeExact", "");
		}
		
		String remoteServerUsername = adminService.getGlobalProperty(OPENMRS_REMOTE_SERVER_USERNAME_GP);
		String remoteServerPassword = adminService.getGlobalProperty(OPENMRS_REMOTE_SERVER_PASSWORD_GP);
		if (StringUtils.hasText(remoteServerUsername) && StringUtils.hasText(remoteServerPassword)) {
			String remoteServerBasicAuth = new StringBuilder(remoteServerUsername).append(":").append(remoteServerPassword)
			        .toString();
			byte[] byteArray = remoteServerBasicAuth.getBytes();
			String base64encoded = Utils.byteArrayToBase64(remoteServerBasicAuth.getBytes(), 0, byteArray.length);
			modelAndView.getModelMap().addAttribute("remoteServerUsername", remoteServerUsername);
			modelAndView.getModelMap().addAttribute("remoteServerPassword", remoteServerPassword);
			modelAndView.getModelMap().addAttribute("remoteServerAuth", base64encoded);
		}
		
		if (StringUtils.isEmpty(remoteServerUsername)) {
			LOGGER.warn("Global property {} not set", OPENMRS_REMOTE_SERVER_USERNAME_GP);
		}
		
		if (StringUtils.isEmpty(remoteServerPassword)) {
			LOGGER.warn("Global property {} not set", OPENMRS_REMOTE_SERVER_PASSWORD_GP);
		}
		
		modelAndView.getModelMap().addAttribute("formatter",
		    DateTimeFormatter.ofPattern(EsaudeFeaturesConstants.DATETIME_DISPLAY_PATTERN));
		return modelAndView;
	}
	
	@RequestMapping(method = RequestMethod.GET, value = "/module/esaudefeatures/openmrsRemotePatients.json", produces = { "application/json " })
	public SimpleObject remotePatientSearch(@RequestParam("text") String searchText) throws Exception {
		return delegate.searchPatients(searchText);
	}
	
	@ResponseStatus(HttpStatus.CREATED)
	@RequestMapping(method = RequestMethod.POST, value = "/module/esaudefeatures/openmrsPatient.json", produces = { "application/json" })
	@ResponseBody
	public String importPatient(@RequestParam("uuid") String patientUuid) {
		try {
			Patient patient = delegate.importPatientWithUuid(patientUuid);
			
			try {
				delegate.importRelationshipsForPerson(patient.getPerson());
			}
			catch (Exception e) {
				// TODO: Tell the user that we failed.
				LOGGER.warn("Could not import relationships for patient with uuid {}", patient.getUuid(), e);
			}
			return patient.getPatientId().toString();
		}
		catch (Exception e) {
			LOGGER.error("An error occured while importing patient with uuid {}", patientUuid, e);
			if (e instanceof RemoteOpenmrsSearchException) {
				throw (RemoteOpenmrsSearchException) e;
			} else {
				throw new RemoteOpenmrsSearchException(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR.value());
			}
		}
	}
	
	@RequestMapping(method = RequestMethod.GET, value = "/module/esaudefeatures/openmrsRemoteGetRequest.json", produces = { "application/json " })
	public SimpleObject remoteGetRequest(HttpServletRequest request) throws Exception {
		if(!request.getParameterMap().containsKey("resource")) {
			throw new RemoteOpenmrsSearchException("Openmrs REST resource not specified", HttpStatus.BAD_REQUEST.value());
		}
		final Map<String, String> params = new HashMap<>(request.getParameterMap().size() - 1);
		for(Object key: request.getParameterMap().keySet()) {
			params.put((String) key, request.getParameter((String) key));
		}
		return delegate.runRemoteOpenmrsRestRequest(params);
	}
}
