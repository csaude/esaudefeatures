package org.openmrs.module.esaudefeatures.api;

import org.openmrs.User;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.APIException;
import org.openmrs.api.OpenmrsService;
import org.springframework.transaction.annotation.Transactional;

public interface RCSService extends OpenmrsService {
	
	@Transactional
	@Authorized
	User getUserByEmail(String email) throws APIException;
	
	@Transactional
	@Authorized
	User getUserBySystemId(String systemId) throws APIException;
	
}
