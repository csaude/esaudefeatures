package org.openmrs.module.esaudefeatures.web;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.StringType;
import org.openmrs.Auditable;
import org.openmrs.BaseOpenmrsMetadata;
import org.openmrs.Concept;
import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PatientIdentifierType;
import org.openmrs.Person;
import org.openmrs.PersonAddress;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.PersonName;
import org.openmrs.Relationship;
import org.openmrs.RelationshipType;
import org.openmrs.User;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.ConceptService;
import org.openmrs.api.LocationService;
import org.openmrs.api.PatientService;
import org.openmrs.api.PersonService;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.esaudefeatures.EsaudeFeaturesConstants;
import org.openmrs.module.esaudefeatures.api.RCSService;
import org.openmrs.module.esaudefeatures.web.exception.RemoteImportException;
import org.openmrs.module.esaudefeatures.web.exception.RemoteOpenmrsSearchException;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.util.PrivilegeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.format.datetime.DateFormatter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.openmrs.module.esaudefeatures.EsaudeFeaturesConstants.HOME_PHONE_PERSON_ATTR_TYPE_UUID;
import static org.openmrs.module.esaudefeatures.EsaudeFeaturesConstants.MOBILE_PHONE_PERSON_ATTR_TYPE_UUID;
import static org.openmrs.module.esaudefeatures.EsaudeFeaturesConstants.FHIR_IDENTIFIER_SYSTEM_FOR_OPENMRS_UUID_GP;
import static org.openmrs.module.esaudefeatures.EsaudeFeaturesConstants.OPENMRS_REMOTE_SERVER_PASSWORD_GP;
import static org.openmrs.module.esaudefeatures.EsaudeFeaturesConstants.OPENMRS_REMOTE_SERVER_URL_GP;
import static org.openmrs.module.esaudefeatures.EsaudeFeaturesConstants.OPENMRS_REMOTE_SERVER_USERNAME_GP;
import static org.openmrs.module.esaudefeatures.EsaudeFeaturesConstants.REMOTE_SERVER_SKIP_HOSTNAME_VERIFICATION_GP;
import static org.openmrs.module.esaudefeatures.web.Utils.generatePassword;
import static org.openmrs.module.esaudefeatures.web.Utils.parseDateString;
import static org.openmrs.util.OpenmrsConstants.OPENMRS_VERSION_SHORT;

/**
 * @uthor Willa Mhawila<a.mhawila@gmail.com> on 2/20/22.
 */
@Component
@Scope("prototype")
public class ImportHelperService {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(ImportHelperService.class);
	private ConceptService conceptService;
	
	private AdministrationService adminService;
	
	private LocationService locationService;
	
	private PatientService patientService;
	
	private PersonService personService;
	
	private UserService userService;
	
	private ConcurrentMap<User, Map<String, Object>> importedUsersCache = new ConcurrentHashMap<>();

	private RCSService rcsService;

	public static final List<String> IGNORED_PERSON_ATTRIBUTE_TYPES = new ArrayList<String>();
	
	static {
		// Health center is not imported to allow for a new value to be propagated.
		IGNORED_PERSON_ATTRIBUTE_TYPES.add("8d87236c-c2cc-11de-8d13-0010c6dffd0f");
	}
	
	private Person dummyPerson;
	
	private User placeholderUser;
	private List<User> usersReferencingDummyPerson = new ArrayList<>();
	private List<User> usersReferencingPlaceholderUser = new ArrayList<>();


	@Autowired
	public void setConceptService(ConceptService conceptService) {
		this.conceptService = conceptService;
	}
	
	@Autowired
	public void setAdminService(AdministrationService adminService) {
		this.adminService = adminService;
	}
	
	@Autowired
	public void setPatientService(PatientService patientService) {
		this.patientService = patientService;
	}
	
	@Autowired
	public void setPersonService(PersonService personService) {
		this.personService = personService;
	}
	
	@Autowired
	public void setLocationService(LocationService locationService) {
		this.locationService = locationService;
	}
	
	@Autowired
	public void setUserService(UserService userService) {
		this.userService = userService;
	}

	@Autowired
	public void setRcsService(RCSService rcsService) {
		this.rcsService = rcsService;
	}

	public Patient getPatientFromFhirPatientResource(org.hl7.fhir.r4.model.Patient fhirPatientResource) {
		Person person = new Person();
		String patientUuidConceptMap = adminService.getGlobalProperty(FHIR_IDENTIFIER_SYSTEM_FOR_OPENMRS_UUID_GP);
		String opencrPatientUuidCode = patientUuidConceptMap.split(":")[0];
		String openmrsUuid = Utils.getOpenmrsUuidFromFhirIdentifiers(fhirPatientResource.getIdentifier(), opencrPatientUuidCode);
		if(openmrsUuid != null) {
			person.setUuid(openmrsUuid);
		} else {
			person.setUuid(fhirPatientResource.getIdElement().getIdPart());
		}
		Patient openmrsPatient = new Patient(person);
		openmrsPatient.setBirthdate(fhirPatientResource.getBirthDate());
		if(fhirPatientResource.hasGender()) {
			openmrsPatient.setGender(String.valueOf(fhirPatientResource.getGender().getDefinition().charAt(0)));
		}

		if(fhirPatientResource.hasDeceasedDateTimeType()) {
			try {
				openmrsPatient.setDeathDate(fhirPatientResource.getDeceasedDateTimeType().getValue());
			} catch (FHIRException e) {
				e.printStackTrace();
			}
		}

		for(HumanName opencrName: fhirPatientResource.getName()) {
			PersonName openmrsName = new PersonName();
			openmrsName.setUuid(opencrName.getId());
			updatePersonNameWithOpencrDetails(openmrsName, opencrName);
			openmrsName.setPerson(person);
			person.addName(openmrsName);
		}

		String identifyTypeConceptMappings =
				adminService.getGlobalProperty(EsaudeFeaturesConstants.FHIR_IDENTIFIER_SYSTEM_TO_OPENMRS_IDENTIFIER_TYPE_UUID_MAPPINGS_GP);
		for(Identifier identifier: fhirPatientResource.getIdentifier()) {
			String openmrsIdentifierTypeUuid = Utils.getOpenmrsIdentifierTypeUuid(identifier, identifyTypeConceptMappings);
			if(openmrsIdentifierTypeUuid != null) {
				PatientIdentifier patientIdentifier = new PatientIdentifier();
				patientIdentifier.setPatient(openmrsPatient);
				patientIdentifier.setIdentifier(identifier.getValue());

				PatientIdentifierType identifierType = patientService.getPatientIdentifierTypeByUuid(openmrsIdentifierTypeUuid);
				patientIdentifier.setIdentifierType(identifierType);
				if(PatientIdentifierType.LocationBehavior.REQUIRED.equals(identifierType.getLocationBehavior())) {
					patientIdentifier.setLocation(locationService.getDefaultLocation());
				}
				openmrsPatient.addIdentifier(patientIdentifier);
			}
		}

		for(Address opencrAddress: fhirPatientResource.getAddress()) {
			PersonAddress openmrsAddress = new PersonAddress();
			openmrsAddress.setUuid(opencrAddress.getId());
			updatePersonAddressWithOpencrDetails(openmrsAddress, opencrAddress);
			openmrsAddress.setPerson(person);
			openmrsPatient.addAddress(openmrsAddress);
		}

		updateOpenmrsPatientContactFromOpencrDetails(openmrsPatient, fhirPatientResource);

		return openmrsPatient;
	}

	public Patient getPatientFromOpenmrsRestPayload(SimpleObject patientObject) throws Exception {
		Person person = getPersonFromOpenmrsRestRepresentation((Map) patientObject.get("person"));
		Patient patient = new Patient(person);
		Map auditInfo = patientObject.get("auditInfo");
		
		if (patientObject.containsKey("identifiers") && patientObject.get("identifiers") != null) {
			updateIdentifiersForPatient(patient);
		}
		
		updateAuditInfo(patient, auditInfo);
		return patient;
	}
	
	protected void updateIdentifiersForPatient(final Patient patient) {
		String[] urlUserPass = getRemoteOpenmrsHostUsernamePassword();
		String[] pathSegments = { "ws/rest/v1/patient", patient.getUuid(), "identifier" };
		String errorMessage = String.format("Could not fetch identifiers for patient with uuid %s from server %s",
		    patient.getUuid(), urlUserPass[0]);
		
		Map<String, String> queryParams = new HashMap<>();
		queryParams.put("v", "full");
		
		Request identifiersRequest = Utils.createBasicAuthGetRequest(urlUserPass[0], urlUserPass[1], urlUserPass[2],
		    pathSegments, queryParams);
		boolean skipHostnameVerification = Boolean.parseBoolean(adminService.getGlobalProperty(
		    REMOTE_SERVER_SKIP_HOSTNAME_VERIFICATION_GP, "FALSE"));
		
		OkHttpClient httpClient;
		try {
			httpClient = Utils.createOkHttpClient(skipHostnameVerification);
		}
		catch (Exception e) {
			LOGGER.error("Could not create an http client", null, e);
			throw new RemoteOpenmrsSearchException(errorMessage, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
		
		Response response = null;
		try {
			response = httpClient.newCall(identifiersRequest).execute();
			if (response.isSuccessful() && response.code() == HttpServletResponse.SC_OK) {
				SimpleObject responseBody = SimpleObject.parseJson(response.body().string());
				List<Map> identifiersMaps = responseBody.get("results");
				Set<PatientIdentifier> identifiers = new TreeSet<>();
				
				for (Map identifierMap : identifiersMaps) {
					if (!(Boolean) identifierMap.get("voided")) {
						PatientIdentifier identifier = new PatientIdentifier();
						identifier.setUuid((String) identifierMap.get("uuid"));
						identifier.setIdentifier((String) identifierMap.get("identifier"));
						identifier.setIdentifierType(patientService
						        .getPatientIdentifierTypeByUuid((String) ((Map) identifierMap.get("identifierType"))
						                .get("uuid")));
						if (identifierMap.containsKey("location") && identifierMap.get("location") != null) {
							String idLocUuid = (String) ((Map) identifierMap.get("location")).get("uuid");
							Location idLocation = locationService.getLocationByUuid(idLocUuid);
							
							if (idLocation == null) {
								idLocation = importLocationFromRemoteOpenmrsServer(idLocUuid);
							}
							identifier.setLocation(idLocation);
						}
						identifier.setPreferred((Boolean) identifierMap.get("preferred"));
						identifier.setPatient(patient);
						
						updateAuditInfo(identifier, (Map<String, Object>) identifierMap.get("auditInfo"));
						identifier.setPatient(patient);
						identifiers.add(identifier);
					}
				}
				patient.setIdentifiers(identifiers);
			} else {
				LOGGER.error("Error when executing http request {} ", identifiersRequest);
				throw new RemoteImportException(errorMessage, HttpStatus.valueOf(response.code()));
			}
		}
		catch (IOException e) {
			LOGGER.error("Error when executing http request {} ", identifiersRequest);
			throw new RemoteImportException(errorMessage, e, HttpStatus.INTERNAL_SERVER_ERROR);
		} finally {
			if(response != null) {
				response.close();
			}
		}
	}
	
	public Person importPersonFromRemoteOpenmrsServer(String personUuid) {
		String[] urlUserPass = getRemoteOpenmrsHostUsernamePassword();
		String message = String.format("Could not fetch person with uuid %s from server %s", personUuid, urlUserPass[0]);
		String[] locationPathSegments = { "ws/rest/v1/person", personUuid };
		Map<String, String> queryParams = new HashMap<>();
		queryParams.put("v", "full");
		
		Request personRequest = Utils.createBasicAuthGetRequest(urlUserPass[0], urlUserPass[1], urlUserPass[2],
		    locationPathSegments, queryParams);
		boolean skipHostnameVerification = Boolean.parseBoolean(adminService.getGlobalProperty(
		    REMOTE_SERVER_SKIP_HOSTNAME_VERIFICATION_GP, "FALSE"));
		OkHttpClient httpClient;
		try {
			httpClient = Utils.createOkHttpClient(skipHostnameVerification);
		}
		catch (Exception e) {
			LOGGER.error("Could not create http client", e);
			throw new RemoteOpenmrsSearchException(message, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
		
		Response response;
		try {
			response = httpClient.newCall(personRequest).execute();
		}
		catch (IOException e) {
			LOGGER.error("Error when executing http request {}", personRequest, e);
			throw new RemoteOpenmrsSearchException(message, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}

		try {
			if (response.isSuccessful() && response.code() == HttpServletResponse.SC_OK) {
				try {
					SimpleObject fetchedPersonObject = SimpleObject.parseJson(response.body().string());
					Person person = getPersonFromOpenmrsRestRepresentation(fetchedPersonObject);
					return personService.savePerson(person);
				} catch (IOException e) {
					LOGGER.error("Error while reading response from server {}", urlUserPass[0], e);
					throw new RemoteImportException(message, e, HttpStatus.INTERNAL_SERVER_ERROR);
				}
			}
			throw new RemoteImportException(response.message(), HttpStatus.INTERNAL_SERVER_ERROR);
		} finally {
			if(response != null) {
				response.close();
			}
		}
	}
	
	public Person getPersonFromOpenmrsRestRepresentation(Map personMap) {
		Person person = new Person();
		person.setUuid((String) personMap.get("uuid"));
		person.setGender((String) personMap.get("gender"));
		person.setBirthdate(parseDateString((String) personMap.get("birthdate")));
		person.setBirthdateEstimated((Boolean) personMap.get("birthdateEstimated"));
		person.setDead((Boolean) personMap.get("dead"));
		String deathDateString = (String) personMap.get("deathDate");
		if (deathDateString != null) {
			person.setDeathDate(parseDateString(deathDateString));
		}
		
		Object causeOfDeath = personMap.get("causeOfDeath");
		if (causeOfDeath != null) {
			String conceptUuid = null;
			if (causeOfDeath instanceof Map) {
				conceptUuid = (String) ((Map) causeOfDeath).get("uuid");
			} else if (causeOfDeath instanceof String) {
				conceptUuid = (String) causeOfDeath;
			}
			
			if (conceptUuid != null) {
				Concept causeOfDeathConcept = conceptService.getConceptByUuid(conceptUuid);
				person.setCauseOfDeath(causeOfDeathConcept);
			}
		}
		
		// Names
		updatePersonNames(person);
		
		if (personMap.get("addresses") != null && ((List) personMap.get("addresses")).size() > 0) {
			updatePersonAddresses(person);
		}
		
		if (personMap.get("attributes") != null && ((List) personMap.get("attributes")).size() > 0) {
			updatePersonAttributes(person);
		}

		if(personMap.get("auditInfo") != null) {
			updateAuditInfo(person, (Map) personMap.get("auditInfo"));
		}

		return person;
	}
	
	protected void updatePersonNames(final Person person) {
		updatePersonCollectionProperty(person, PersonName.class, "name", "setNames");
	}
	
	protected void updatePersonAddresses(final Person person) {
		updatePersonCollectionProperty(person, PersonAddress.class, "address", "setAddresses");
	}
	
	protected <T extends Auditable> void updatePersonCollectionProperty(final Person person, Class<T> propretyClass,
	        String restSubResource, String setterMethod) {
		String[] urlUserPass = getRemoteOpenmrsHostUsernamePassword();
		String[] pathSegments = { "ws/rest/v1/person", person.getUuid(), restSubResource };
		String errorMessage = String.format("Could not fetch and/or update %s for person with uuid %s from server %s",
		    restSubResource, person.getUuid(), urlUserPass[0]);
		
		Map<String, String> queryParams = new HashMap<>();
		queryParams.put("v", "full");
		
		Request namesRequest = Utils.createBasicAuthGetRequest(urlUserPass[0], urlUserPass[1], urlUserPass[2], pathSegments,
		    queryParams);
		boolean skipHostnameVerification = Boolean.parseBoolean(adminService.getGlobalProperty(
		    REMOTE_SERVER_SKIP_HOSTNAME_VERIFICATION_GP, "FALSE"));
		
		OkHttpClient httpClient;
		try {
			httpClient = Utils.createOkHttpClient(skipHostnameVerification);
		}
		catch (Exception e) {
			LOGGER.error("Could not create an http client", null, e);
			throw new RemoteImportException(errorMessage, HttpStatus.INTERNAL_SERVER_ERROR);
		}
		
		Response response = null;
		try {
			response = httpClient.newCall(namesRequest).execute();
			if (response.isSuccessful() && response.code() == HttpServletResponse.SC_OK) {
				SimpleObject responseBody = SimpleObject.parseJson(response.body().string());
				List<Map> propertyMaps = responseBody.get("results");
				Set<T> properties = new TreeSet<>();
				
				for (final Map propertyMap : propertyMaps) {
					if (!(Boolean) propertyMap.get("voided")) {
						final T personProperty = propretyClass.newInstance();
						ReflectionUtils.doWithFields(propretyClass, new ReflectionUtils.FieldCallback() {
							
							@Override
							public void doWith(Field field) throws IllegalArgumentException, IllegalAccessException {
								field.setAccessible(true);
								// EFM-37 Quick fix
								if(Date.class.equals(field.getType())) {
									field.set(personProperty, parseDateString((String) propertyMap.get(field.getName())));
								} else {
									field.set(personProperty, propertyMap.get(field.getName()));
								}
								field.setAccessible(false);
							}
						}, new ReflectionUtils.FieldFilter() {
							
							@Override
							public boolean matches(Field field) {
								int modifiers = field.getModifiers();
								if (!ClassUtils.isPrimitiveOrWrapper(field.getClass())
								        && String.class.equals(field.getClass())) {
									return false;
								}
								return (!Modifier.isFinal(modifiers) && !Modifier.isStatic(modifiers));
							}
						});
						
						updateAuditInfo(personProperty, (Map) propertyMap.get("auditInfo"));
						
						try {
							Method method = propretyClass.getMethod("setPerson", Person.class);
							method.invoke(personProperty, person);
						}
						catch (NoSuchMethodException nsme) {
							LOGGER.warn("No method setPerson on class {}. Ignoring...", propretyClass.getName());
						}
						catch (InvocationTargetException e) {
							LOGGER.warn("Could not invoke setPerson on instance of {}", propretyClass.getName());
						}
						properties.add(personProperty);
					}
				}
				
				Method method = Person.class.getMethod(setterMethod, Set.class);
				method.invoke(person, properties);
			} else {
				LOGGER.error("Error when executing http request {} ", namesRequest);
				throw new RemoteImportException(errorMessage, HttpStatus.valueOf(response.code()));
			}
		}
		catch (IOException e) {
			LOGGER.error("Error when executing http request {} ", namesRequest);
			throw new RemoteImportException(errorMessage, e, HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch (IllegalAccessException e) {
			LOGGER.error("Error while attempting instantiation of {}", propretyClass, e);
			throw new RemoteImportException(errorMessage, e, HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch (InstantiationException e) {
			LOGGER.error("Error instantiating class {}", propretyClass, e);
			throw new RemoteImportException(errorMessage, e, HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch (NoSuchMethodException e) {
			LOGGER.error("Method {} not found on Person class", setterMethod, e);
			throw new RemoteImportException(errorMessage, e, HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch (InvocationTargetException e) {
			LOGGER.error("Error invoking method {} on instance of org.openmrs.Person class", setterMethod, e);
			throw new RemoteImportException(errorMessage, e, HttpStatus.INTERNAL_SERVER_ERROR);
		} finally {
			if(response != null) {
				response.close();
			}
		}
	}
	
	protected void updatePersonAttributes(final Person person) {
		String[] urlUserPass = getRemoteOpenmrsHostUsernamePassword();
		String[] pathSegments = { "ws/rest/v1/person", person.getUuid(), "attribute" };
		String errorMessage = String.format(
		    "Could not fetch and/or update attributes for person with uuid %s from server %s", person.getUuid(),
		    urlUserPass[0]);
		
		Map<String, String> queryParams = new HashMap<>();
		queryParams.put("v", "full");
		
		Request namesRequest = Utils.createBasicAuthGetRequest(urlUserPass[0], urlUserPass[1], urlUserPass[2], pathSegments,
		    queryParams);
		boolean skipHostnameVerification = Boolean.parseBoolean(adminService.getGlobalProperty(
		    REMOTE_SERVER_SKIP_HOSTNAME_VERIFICATION_GP, "FALSE"));
		
		OkHttpClient httpClient;
		try {
			httpClient = Utils.createOkHttpClient(skipHostnameVerification);
		}
		catch (Exception e) {
			LOGGER.error("Could not create an http client", null, e);
			throw new RemoteOpenmrsSearchException(errorMessage, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
		
		Response response = null;
		try {
			response = httpClient.newCall(namesRequest).execute();
			if (response.isSuccessful() && response.code() == HttpServletResponse.SC_OK) {
				SimpleObject responseBody = SimpleObject.parseJson(response.body().string());
				List<Map> attributesMaps = responseBody.get("results");
				Set<PersonAttribute> personAttributes = new TreeSet<PersonAttribute>();
				
				for (final Map attributeMap : attributesMaps) {
					String personAttributeTypeUuid = (String) ((Map) attributeMap.get("attributeType")).get("uuid");
					if (IGNORED_PERSON_ATTRIBUTE_TYPES.contains(personAttributeTypeUuid)) {
						continue;
					}
					
					if (!(Boolean) attributeMap.get("voided")) {
						PersonAttribute personAttribute = new PersonAttribute();
						personAttribute.setUuid((String) attributeMap.get("uuid"));
						
						PersonAttributeType personAttributeType = personService
						        .getPersonAttributeTypeByUuid(personAttributeTypeUuid);
						personAttribute.setAttributeType(personAttributeType);
						
						Object attributeValue = attributeMap.get("value");
						if (attributeValue instanceof Map) {
							if ("org.openmrs.Concept".equals(personAttributeType.getFormat())) {
								Concept concept = conceptService.getConceptByUuid((String) ((Map) attributeValue)
								        .get("uuid"));
								personAttribute.setValue(concept.getConceptId().toString());
							} else if ("org.openmrs.Location".equals(personAttributeType.getFormat())) {
								// Location is not harmonized hence some work needs to be done here.
								String locationUuid = (String) ((Map) attributeValue).get("uuid");
								Location location = locationService.getLocationByUuid(locationUuid);
								if (location == null) {
									// Import this location from central (possibly including its ancestors)
									location = importLocationFromRemoteOpenmrsServer(locationUuid);
								}
								personAttribute.setValue(location.getLocationId().toString());
							} else {
								// We gonna go on a limb and set the uuid (assuming it is openmrs domain object) as is (What could happen here?) (._.)
								personAttribute.setValue((String) ((Map) attributeValue).get("uuid"));
							}
						} else {
							personAttribute.setValue(String.valueOf(attributeValue));
						}
						updateAuditInfo(personAttribute, (Map) attributeMap.get("auditInfo"));
						personAttribute.setPerson(person);
						personAttributes.add(personAttribute);
					}
				}
				person.setAttributes(personAttributes);
			} else {
				LOGGER.error("Error when executing http request {} ", namesRequest);
				throw new RemoteImportException(errorMessage, HttpStatus.valueOf(response.code()));
			}
		}
		catch (IOException e) {
			LOGGER.error("Error when executing http request {} ", namesRequest);
			throw new RemoteImportException(errorMessage, e, HttpStatus.INTERNAL_SERVER_ERROR);
		} finally {
			if(response != null) {
				response.close();
			}
		}
	}
	
	public void updateAuditInfo(Auditable openmrsObject, Map<String, Object> auditInfo) {
		if (openmrsObject instanceof Person) {
			((Person) openmrsObject).setPersonDateCreated(parseDateString((String) auditInfo.get("dateCreated")));
			((Person) openmrsObject).setPersonDateChanged(parseDateString((String) auditInfo.get("dateChanged")));
		} else {
			openmrsObject.setDateCreated(parseDateString((String) auditInfo.get("dateCreated")));
			openmrsObject.setDateChanged(parseDateString((String) auditInfo.get("dateChanged")));
		}
		if (openmrsObject instanceof BaseOpenmrsMetadata) {
			((BaseOpenmrsMetadata) openmrsObject).setDateRetired(parseDateString((String) auditInfo.get("dateRetired")));
		}
		
		Map creatorMap = (Map) auditInfo.get("creator");
		if (creatorMap != null) {
			String creatorUuid = (String) creatorMap.get("uuid");
			User creator = userService.getUserByUuid(creatorUuid);
			if (creator == null) {
				creator = searchAndImportUserFromImportedUsersCache(creatorUuid);
				if(creator == null) {
					creator = importUserFromRemoteOpenmrsServer(creatorUuid);
				}
			}
			
			if (openmrsObject instanceof Person) {
				((Person) openmrsObject).setPersonCreator(creator);
			} else {
				if(openmrsObject instanceof User && placeholderUser != null && placeholderUser.equals(openmrsObject.getCreator())) {
					usersReferencingPlaceholderUser.remove(openmrsObject);
				}
				openmrsObject.setCreator(creator);
			}
		}
		
		Map changerMap = (Map) auditInfo.get("changedBy");
		if (changerMap != null) {
			String changerUuid = (String) changerMap.get("uuid");
			User changer = userService.getUserByUuid(changerUuid);
			if (changer == null) {
				changer = searchAndImportUserFromImportedUsersCache(changerUuid);
				if(changer == null) {
					changer = importUserFromRemoteOpenmrsServer(changerUuid);
				}
			}
			
			if (openmrsObject instanceof Person) {
				((Person) openmrsObject).setPersonChangedBy(changer);
			} else {
				if(openmrsObject instanceof User && placeholderUser != null && placeholderUser.equals(openmrsObject.getChangedBy())) {
					usersReferencingPlaceholderUser.remove(openmrsObject);
				}
				openmrsObject.setChangedBy(changer);
			}
		}
		
		if (openmrsObject instanceof BaseOpenmrsMetadata) {
			Map retireeMap = (Map) auditInfo.get("retiredBy");
			if (retireeMap != null) {
				BaseOpenmrsMetadata metadata = (BaseOpenmrsMetadata) openmrsObject;
				String retirerUuid = (String) retireeMap.get("uuid");
				User retirer = userService.getUserByUuid(retirerUuid);
				
				if (retirer == null) {
					retirer = searchAndImportUserFromImportedUsersCache(retirerUuid);
					if(retirer == null) {
						retirer = importUserFromRemoteOpenmrsServer(retirerUuid);
					}
				}
				if(metadata instanceof User && placeholderUser != null && placeholderUser.equals(metadata.getRetiredBy())) {
					usersReferencingPlaceholderUser.remove(openmrsObject);
				}
				metadata.setRetiredBy(retirer);
				metadata.setRetired(true);
				metadata.setRetireReason((String) auditInfo.get("retireReason"));
			}
		}
	}
	
	private int importUserCallCount = 0;
	
	public User importUserFromRemoteOpenmrsServer(String userUuid) {
		LOGGER.info("Importing user with uuid {}", userUuid);
		String[] urlUserPass = getRemoteOpenmrsHostUsernamePassword();
		String errorMessage = String.format("Could not fetch user with uuid %s from server %s", userUuid, urlUserPass[0]);
		String[] pathSegments = { "ws/rest/v1/user", userUuid };
		
		Map<String, String> queryParams = new HashMap<>();
		queryParams.put("v", "full");
		
		Request userRequest = Utils.createBasicAuthGetRequest(urlUserPass[0], urlUserPass[1], urlUserPass[2], pathSegments,
		    queryParams);
		boolean skipHostnameVerification = Boolean.parseBoolean(adminService.getGlobalProperty(
		    REMOTE_SERVER_SKIP_HOSTNAME_VERIFICATION_GP, "FALSE"));
		
		OkHttpClient httpClient;
		try {
			httpClient = Utils.createOkHttpClient(skipHostnameVerification);
		}
		catch (Exception e) {
			LOGGER.error("Could not create an http client", null, e);
			throw new RemoteImportException(errorMessage, e, HttpStatus.INTERNAL_SERVER_ERROR);
		}
		
		Response response = null;
		try {
			response = httpClient.newCall(userRequest).execute();
			if (response.isSuccessful() && response.code() == HttpServletResponse.SC_OK) {
				SimpleObject fetchedUser = SimpleObject.parseJson(response.body().string());
                String fetchedUsername = (String) fetchedUser.get("username");
                String uuid = (String) fetchedUser.get("uuid");
                String systemId = (String) fetchedUser.get("systemId");

                User user = new User();
				user.setUuid(uuid);

				User conflictingUserByUserName = userService.getUserByUsername(fetchedUsername);
				conflictingUserByUserName = conflictingUserByUserName == null ? rcsService.getUserBySystemId(fetchedUsername) : conflictingUserByUserName;

				User conflictingUserBySystemId = rcsService.getUserBySystemId(systemId);
				conflictingUserBySystemId = conflictingUserBySystemId == null ? userService.getUserByUsername(systemId) : conflictingUserBySystemId;

				String finalUsername;
				String finalSystemId;

				// Harmonize username
				if (conflictingUserByUserName != null) {
					finalUsername = fetchedUsername + "_"  + uuid;
					int maxLength = 50;
					if (finalUsername.length() > maxLength) {
						finalUsername = finalUsername.substring(0, maxLength);
					}
					user.setUsername(finalUsername);
				}else {
					user.setUsername(fetchedUsername);
				}

				// Harmonize the systemId
				if (conflictingUserBySystemId != null) {
					finalSystemId = systemId + "_"  + uuid;
					int maxLength = 50;
					if (finalSystemId.length() > maxLength) {
						finalSystemId = finalSystemId.substring(0, maxLength);
					}
					user.setSystemId(finalSystemId);
				}else {
					user.setSystemId(systemId);
				}

				// Cache the user before calling import person because potentially this might need to import users too.
				importedUsersCache.put(user, fetchedUser);
				String personUuid = (String) ((Map) fetchedUser.get("person")).get("uuid");
				Person person = personService.getPersonByUuid(personUuid);
				if (person == null) {
					person = importPersonFromRemoteOpenmrsServer(personUuid);
				}

				if(dummyPerson != null && dummyPerson.equals(user.getPerson())) {
					usersReferencingDummyPerson.remove(user);
				}

				user.setPerson(person);
				
				if (fetchedUser.containsKey("userProperties") && fetchedUser.get("userProperties") != null) {
					user.setUserProperties((Map) fetchedUser.get("userProperties"));
				}

				updateAuditInfo(user, (Map) fetchedUser.get("auditInfo"));

				// Unfortunately if the user already exists the API will update changedBy to currently logged in user and dateChanged to now.
				// User can exists if they are imported with placeholder values in circular dependencies where the same user is referenced in another
				// nested openmrs object being imported before being imported.
				// TODO: Need to find a workaound to address this limitation.
				return persistUser(user);
			}
			throw new RemoteImportException(errorMessage, HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch (IOException e) {
			LOGGER.error("Error when executing http request {} ", userRequest, e);
			throw new RemoteImportException(errorMessage, e, HttpStatus.INTERNAL_SERVER_ERROR);
		} finally {
			// Delete the dummyPerson and placeholder user if the method is the first call
			if(placeholderUser != null && usersReferencingPlaceholderUser.isEmpty()) {
				try {
					Context.addProxyPrivilege(PrivilegeConstants.PURGE_USERS);
					userService.purgeUser(placeholderUser);
					placeholderUser = null;
				} finally {
					Context.removeProxyPrivilege(PrivilegeConstants.PURGE_USERS);
				}
			}

			if(dummyPerson != null && usersReferencingDummyPerson.isEmpty()) {
				try {
					Context.addProxyPrivilege(PrivilegeConstants.PURGE_PERSONS);
					personService.purgePerson(dummyPerson);
					dummyPerson = null;
				} finally {
					Context.removeProxyPrivilege(PrivilegeConstants.PURGE_PERSONS);
				}
			}
			if(response != null) {
				response.close();
			}
		}
	}

	public Location importLocationFromRemoteOpenmrsServer(String locationUuid) {
		String[] urlUserPass = getRemoteOpenmrsHostUsernamePassword();
		String message = String.format("Could not fetch location with uuid %s from server %s", locationUuid, urlUserPass[0]);
		String[] locationPathSegments = { "ws/rest/v1/location", locationUuid };
		Map<String, String> queryParams = new HashMap<>();
		queryParams.put("v", "full");
		
		Request locRequest = Utils.createBasicAuthGetRequest(urlUserPass[0], urlUserPass[1], urlUserPass[2],
		    locationPathSegments, queryParams);
		boolean skipHostnameVerification = Boolean.parseBoolean(adminService.getGlobalProperty(
		    REMOTE_SERVER_SKIP_HOSTNAME_VERIFICATION_GP, "FALSE"));
		OkHttpClient httpClient;
		try {
			httpClient = Utils.createOkHttpClient(skipHostnameVerification);
		}
		catch (Exception e) {
			LOGGER.error("Could not create http client", e);
			throw new RemoteImportException(message, e, HttpStatus.INTERNAL_SERVER_ERROR);
		}
		
		Response response;
		try {
			response = httpClient.newCall(locRequest).execute();
		}
		catch (IOException e) {
			LOGGER.error("Error when executing http request {}", locRequest, e);
			throw new RemoteImportException(message, e, HttpStatus.INTERNAL_SERVER_ERROR);
		}

		try {
			if (response.isSuccessful() && response.code() == HttpServletResponse.SC_OK) {
				final SimpleObject fetchedLocationObject;
				try {
					fetchedLocationObject = SimpleObject.parseJson(response.body().string());
				} catch (IOException e) {
					LOGGER.error("Error while reading response from server {}", urlUserPass[0], e);
					throw new RemoteOpenmrsSearchException(message, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				}
				// TODO: Currently location tags and attributes are not being used so we can ignore them. However a complete solution will have to take
				// these into account.

				final Location fetchedLocation = new Location();
				ReflectionUtils.doWithFields(Location.class, new ReflectionUtils.FieldCallback() {

					@Override
					public void doWith(Field field) throws IllegalArgumentException, IllegalAccessException {
						field.setAccessible(true);
						field.set(fetchedLocation, fetchedLocationObject.get(field.getName()));
						field.setAccessible(false);
					}
				}, new ReflectionUtils.FieldFilter() {

					@Override
					public boolean matches(Field field) {
						if (Arrays.asList("tags", "parentLocation", "childLocations", "attributes").contains(field.getName())) {
							return false;
						}
						int modifiers = field.getModifiers();
						return (!Modifier.isFinal(modifiers) && !Modifier.isStatic(modifiers));
					}
				});

				if (fetchedLocationObject.containsKey("auditInfo")) {
					updateAuditInfo(fetchedLocation, (Map) fetchedLocationObject.get("auditInfo"));
				}

				if (fetchedLocationObject.containsKey("parentLocation") && fetchedLocationObject.get("parentLocation") != null) {
					// Check if this location exists locally
					String parentLocationUuid = (String) ((Map) fetchedLocationObject.get("parentLocation")).get("uuid");
					Location parentLocation = locationService.getLocationByUuid(parentLocationUuid);

					if (parentLocation == null) {
						parentLocation = importLocationFromRemoteOpenmrsServer(parentLocationUuid);
					}
					fetchedLocation.setParentLocation(parentLocation);
				}
				return locationService.saveLocation(fetchedLocation);
			}
			throw new RemoteImportException(message, HttpStatus.INTERNAL_SERVER_ERROR);
		} finally {
			if(response != null) {
				response.close();
			}
		}
	}
	
	public String[] getRemoteOpenmrsHostUsernamePassword() throws RemoteOpenmrsSearchException {
		String message = "Could not fetch data, Global property %s not set";
		String remoteServerUrl = adminService.getGlobalProperty(OPENMRS_REMOTE_SERVER_URL_GP);
		String remoteServerUsername = adminService.getGlobalProperty(OPENMRS_REMOTE_SERVER_USERNAME_GP);
		String remoteServerPassword = adminService.getGlobalProperty(OPENMRS_REMOTE_SERVER_PASSWORD_GP);
		if (StringUtils.isEmpty(remoteServerUrl)) {
			LOGGER.warn(String.format(message, OPENMRS_REMOTE_SERVER_URL_GP));
			throw new RemoteOpenmrsSearchException(message, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
		if (StringUtils.isEmpty(remoteServerUsername)) {
			LOGGER.warn(String.format(message, OPENMRS_REMOTE_SERVER_USERNAME_GP));
			throw new RemoteOpenmrsSearchException(message, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
		if (StringUtils.isEmpty(remoteServerPassword)) {
			LOGGER.warn(String.format(message, OPENMRS_REMOTE_SERVER_PASSWORD_GP));
			throw new RemoteOpenmrsSearchException(message, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
		
		return new String[] { remoteServerUrl, remoteServerUsername, remoteServerPassword };
	}
	
	public Patient updateOpenmrsPatientWithMPIData(Patient openmrsPatient, org.hl7.fhir.r4.model.Patient opencrPatient) {
		openmrsPatient.setBirthdate(opencrPatient.getBirthDate());
		if(opencrPatient.hasGender()) {
			openmrsPatient.setGender(String.valueOf(opencrPatient.getGender().getDefinition().charAt(0)));
		}

		if(opencrPatient.hasDeceasedDateTimeType()) {
			try {
				openmrsPatient.setDeathDate(opencrPatient.getDeceasedDateTimeType().getValue());
			} catch (FHIRException e) {
				e.printStackTrace();
			}
		}

		for(HumanName opencrName: opencrPatient.getName()) {
			for(PersonName openmrsName: openmrsPatient.getNames()) {
				if(opencrName.getId().equals(openmrsName.getUuid())) {
					updatePersonNameWithOpencrDetails(openmrsName, opencrName);
					break;
				}
			}
		}

		String identifyTypeConceptMappings =
				adminService.getGlobalProperty(EsaudeFeaturesConstants.FHIR_IDENTIFIER_SYSTEM_TO_OPENMRS_IDENTIFIER_TYPE_UUID_MAPPINGS_GP);
		for(Identifier identifier: opencrPatient.getIdentifier()) {
			String openmrsIdentifierTypeUuid = Utils.getOpenmrsIdentifierTypeUuid(identifier, identifyTypeConceptMappings);
			if(openmrsIdentifierTypeUuid != null) {
				for(PatientIdentifier patientIdentifier: openmrsPatient.getIdentifiers()) {
					if(openmrsIdentifierTypeUuid.equals(patientIdentifier.getIdentifierType().getUuid())) {
						patientIdentifier.setIdentifier(identifier.getValue());
					}
				}
			}
		}

		for(Address opencrAddress: opencrPatient.getAddress()) {
			for(PersonAddress openmrsAddress: openmrsPatient.getAddresses()) {
				updatePersonAddressWithOpencrDetails(openmrsAddress, opencrAddress);
			}
		}

		updateOpenmrsPatientContactFromOpencrDetails(openmrsPatient, opencrPatient);

		return openmrsPatient;
	}

	public List<Relationship> importRelationshipsForPerson(Person person) {
		String[] urlUserPass = getRemoteOpenmrsHostUsernamePassword();
		String message = String.format("Could not fetch relationships for person with uuid %s from server %s", person.getUuid(), urlUserPass[0]);
		String[] locationPathSegments = { "ws/rest/v1/relationship" };
		Map<String, String> queryParams = new HashMap<>();
		queryParams.put("v", "full");
		queryParams.put("person", person.getUuid());

		Request relationshipRequest = Utils.createBasicAuthGetRequest(urlUserPass[0], urlUserPass[1], urlUserPass[2],
				locationPathSegments, queryParams);
		boolean skipHostnameVerification = Boolean.parseBoolean(adminService.getGlobalProperty(
				REMOTE_SERVER_SKIP_HOSTNAME_VERIFICATION_GP, "FALSE"));
		OkHttpClient httpClient;
		try {
			httpClient = Utils.createOkHttpClient(skipHostnameVerification);
		}
		catch (Exception e) {
			LOGGER.error("Could not create http client", e);
			throw new RemoteOpenmrsSearchException(message, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}

		Response response;
		try {
			response = httpClient.newCall(relationshipRequest).execute();
		}
		catch (IOException e) {
			LOGGER.error("Error when executing http request {}", relationshipRequest, e);
			throw new RemoteOpenmrsSearchException(message, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}

		try {
			if (response.isSuccessful() && response.code() == HttpServletResponse.SC_OK) {
				final SimpleObject fetchedRelationshipsObject;
				try {
					fetchedRelationshipsObject = SimpleObject.parseJson(response.body().string());
				} catch (IOException e) {
					LOGGER.error("Error while reading response from server {}", urlUserPass[0], e);
					throw new RemoteOpenmrsSearchException(message, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				}

				List<Relationship> importedRelationships = new ArrayList<>();
				if (fetchedRelationshipsObject.containsKey("results") && !((List) fetchedRelationshipsObject.get("results")).isEmpty()) {
					List<Map<String, Object>> relationshipObjects = fetchedRelationshipsObject.get("results");
					for (Map<String, Object> relationshipObject : relationshipObjects) {
						Map<String, Object> personAObject = (Map) relationshipObject.get("personA");
						Map<String, Object> personBObject = (Map) relationshipObject.get("personB");
						String relationshipTypeUuid = (String) ((Map<String, Object>) relationshipObject.get("relationshipType")).get("uuid");
						RelationshipType relationshipType = personService.getRelationshipTypeByUuid(relationshipTypeUuid);

						Relationship relationship = new Relationship();
						relationship.setRelationshipType(relationshipType);
						relationship.setUuid((String) relationshipObject.get("uuid"));

						if (person.getUuid().equals(personAObject.get("uuid"))) {
							relationship.setPersonA(person);

							String personBUuid = (String) personBObject.get("uuid");
							Person personB = personService.getPersonByUuid(personBUuid);
							if (personB == null) {
								personB = importPersonFromRemoteOpenmrsServer(personBUuid);
							}

							relationship.setPersonB(personB);
						} else {
							relationship.setPersonB(person);

							String personAUuid = (String) personBObject.get("uuid");
							Person personA = personService.getPersonByUuid(personAUuid);
							if (personA == null) {
								personA = importPersonFromRemoteOpenmrsServer(personAUuid);
							}

							relationship.setPersonA(personA);
						}

						relationship.setStartDate(parseDateString((String) relationshipObject.get("startDate")));
						relationship.setEndDate(parseDateString((String) relationshipObject.get("endDate")));
						updateAuditInfo(relationship, (Map<String, Object>) relationshipObject.get("auditInfo"));
						relationship = personService.saveRelationship(relationship);
						importedRelationships.add(relationship);
					}
				}
				return importedRelationships;
			}
			throw new RemoteImportException(message, HttpStatus.INTERNAL_SERVER_ERROR);
		} finally {
			if(response != null) {
				response.close();
			}
		}
	}

	private Person getDummyPerson() {
		if (dummyPerson != null) {
			return dummyPerson;
		}
		
		dummyPerson = new Person();
		dummyPerson.setUuid(UUID.randomUUID().toString());
		PersonName dummyName = new PersonName("EsaudeFeatures", "DUMMY", "PLACEHOLDER");
		dummyName.setPerson(dummyPerson);
		dummyPerson.addName(dummyName);
		dummyPerson.setGender("F");
		return personService.savePerson(dummyPerson);
	}

	private User getPlaceholderUser() {
		if (placeholderUser != null) {
			return placeholderUser;
		}

		placeholderUser = new User();
		placeholderUser.setSystemId("PLACE-HOLDER-FOR-CENTRAL");
		placeholderUser.setUuid(UUID.randomUUID().toString());
		placeholderUser.setPerson(getDummyPerson());
		placeholderUser.setCreator(userService.getUserByUsername("admin"));
		return userService.createUser(placeholderUser, generatePassword());
	}

	private User searchAndImportUserFromImportedUsersCache(String userUuid) {
		for(Map.Entry<User, Map<String, Object>> entry: importedUsersCache.entrySet()) {
			if (userUuid.equals(entry.getKey().getUuid())) {
				LOGGER.debug("importedUserCache HIT");
				// We need to persist this user already, probably with a dummy person associated with it.
				// Create a place holder user for creator/changer/retiree if provided and not yet filled
				User cachedUser = entry.getKey();
				Map cachedUserObject = entry.getValue();
				if(cachedUser.getPerson() == null && cachedUserObject.containsKey("person")) {
					usersReferencingDummyPerson.add(cachedUser);
					cachedUser.setPerson(getDummyPerson());
				}

				//Audit info
				Map userAuditInfo = (Map) cachedUserObject.get("auditInfo");
				if(userAuditInfo.get("creator") != null && cachedUser.getCreator() == null) {
					String creatorUuid = (String) ((Map) userAuditInfo.get("creator")).get("uuid");
					User creator = userService.getUserByUuid(creatorUuid);
					if (creator == null) {
						usersReferencingPlaceholderUser.add(cachedUser);
						cachedUser.setCreator(getPlaceholderUser());
					} else {
						cachedUser.setCreator(creator);
					}
				}

				if(userAuditInfo.get("changedBy") != null && cachedUser.getChangedBy() == null) {
					String changerUuid = (String) ((Map) userAuditInfo.get("changedBy")).get("uuid");
					User changer = userService.getUserByUuid(changerUuid);
					if (changer == null) {
						cachedUser.setChangedBy(getPlaceholderUser());
					} else {
						cachedUser.setChangedBy(changer);
					}
				}

				if(userAuditInfo.get("retiredBy") != null && cachedUser.getRetiredBy() == null) {
					String retireeUuid = (String) ((Map) userAuditInfo.get("retiredBy")).get("uuid");
					User retiree = userService.getUserByUuid(retireeUuid);
					if (retiree == null) {
						cachedUser.setRetiredBy(getPlaceholderUser());
					} else {
						cachedUser.setRetiredBy(retiree);
					}
				}

				importedUsersCache.remove(cachedUser);
				return persistUser(cachedUser);
			}
		}
		return null;
	}

	private User persistUser(User user) {
		try {
			// Elevate the user temporarily
			Context.addProxyPrivilege(PrivilegeConstants.EDIT_USERS);
			if (OPENMRS_VERSION_SHORT.startsWith("1")) {
				return userService.saveUser(user, generatePassword());
			} else {
				if (user.getUserId() != null) {
					// Reflectively get saveUser(User.class) method.
					try {
						Method saveUserMethod = UserService.class.getMethod("saveUser", User.class);
						saveUserMethod.invoke(userService, user);
						return user;
					} catch (NoSuchMethodException e) {
						LOGGER.error("Error persisting user", e);
						throw new RemoteImportException(e.getMessage(), e, HttpStatus.INTERNAL_SERVER_ERROR);
					} catch (IllegalAccessException e) {
						throw new RemoteImportException(e.getMessage(), e, HttpStatus.INTERNAL_SERVER_ERROR);
					} catch (InvocationTargetException e) {
						throw new RemoteImportException(e.getMessage(), e, HttpStatus.INTERNAL_SERVER_ERROR);
					}
				} else {
					return userService.createUser(user, generatePassword());
				}
			}
		} finally {
			Context.removeProxyPrivilege(PrivilegeConstants.EDIT_USERS);
		}
	}

	private void updatePersonAddressWithOpencrDetails(PersonAddress openmrsAddress, Address opencrAddress) {
		if(opencrAddress.getId().equals(openmrsAddress.getUuid())) {
			if(opencrAddress.hasDistrict()) {
				openmrsAddress.setCountyDistrict(opencrAddress.getDistrict());
			}
			if(opencrAddress.hasState()) {
				openmrsAddress.setStateProvince(opencrAddress.getState());
			}
			if(opencrAddress.hasCountry()) {
				openmrsAddress.setCountry(opencrAddress.getCountry());
			}
			if(opencrAddress.hasPostalCode()) {
				openmrsAddress.setPostalCode(opencrAddress.getPostalCode());
			}

			/**
			 * From Wyclif's email (confirmed by Eurico)
			 line[0] -> address2
			 line[1] -> address6
			 line[2] -> address5
			 line[3] -> address3
			 line[4] -> address1
			 */
			if(opencrAddress.hasLine()) {
				List<StringType> lines = opencrAddress.getLine();
				openmrsAddress.setAddress2(lines.get(0).getValueNotNull());
				if(lines.size() >= 2) {
					openmrsAddress.setAddress6(lines.get(1).getValueNotNull());
				}
				if(lines.size() >= 3) {
					openmrsAddress.setAddress5(lines.get(2).getValueNotNull());
				}
				if(lines.size() >= 4) {
					openmrsAddress.setAddress3(lines.get(3).getValueNotNull());
				}
				if(lines.size() >= 5) {
					openmrsAddress.setAddress1(lines.get(4).getValueNotNull());
				}
			}
		}
	}

	private void updateOpenmrsPatientContactFromOpencrDetails(Patient openmrsPatient, org.hl7.fhir.r4.model.Patient opencrPatient) {
		PersonAttributeType homePhoneAttrType = personService.getPersonAttributeTypeByUuid(HOME_PHONE_PERSON_ATTR_TYPE_UUID);
		PersonAttributeType mobilePhoneAttrType = personService.getPersonAttributeTypeByUuid(MOBILE_PHONE_PERSON_ATTR_TYPE_UUID);
		for(ContactPoint contact: opencrPatient.getTelecom()) {
			if(contact.hasUse()) {
				if(ContactPoint.ContactPointUse.HOME.equals(contact.getUse())) {
					PersonAttribute homePhone = openmrsPatient.getAttribute(homePhoneAttrType);
					if (homePhone == null) {
						openmrsPatient.addAttribute(new PersonAttribute(homePhoneAttrType, contact.getValue()));
					} else {
						homePhone.setValue(contact.getValue());
					}
				} else if(ContactPoint.ContactPointUse.MOBILE.equals(contact.getUse())) {
					PersonAttribute mobilePhone = openmrsPatient.getAttribute(mobilePhoneAttrType);
					if (mobilePhone == null) {
						openmrsPatient.addAttribute(new PersonAttribute(mobilePhoneAttrType, contact.getValue()));
					} else {
						mobilePhone.setValue(contact.getValue());
					}
				}
			}
		}
	}

	private void updatePersonNameWithOpencrDetails(PersonName openmrsName, HumanName opencrName) {
		if(opencrName.hasUse() && HumanName.NameUse.OFFICIAL.equals(opencrName.getUse())) {
			openmrsName.setPreferred(true);
		}
		openmrsName.setFamilyName(opencrName.getFamily());
		if(opencrName.hasGiven()) {
			List<StringType> opencrGiveNames = opencrName.getGiven();
			openmrsName.setGivenName(opencrGiveNames.get(0).getValueNotNull());
			if(opencrGiveNames.size() >= 2) {
				openmrsName.setMiddleName(opencrGiveNames.get(1).getValueNotNull());
			}
		}
	}
}
