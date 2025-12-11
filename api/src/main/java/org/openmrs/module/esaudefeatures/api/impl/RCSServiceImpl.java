package org.openmrs.module.esaudefeatures.api.impl;

import org.openmrs.User;
import org.openmrs.api.APIException;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.esaudefeatures.api.RCSService;
import org.openmrs.module.esaudefeatures.api.dao.RCSDao;

public class RCSServiceImpl extends BaseOpenmrsService implements RCSService {
	
	protected RCSDao rcsDao;
	
	public void setRcsDao(RCSDao rcsDao) {
		this.rcsDao = rcsDao;
	}
	
	@Override
	public User getUserByEmail(String email) throws APIException {
		return rcsDao.getUserByEmail(email);
	}
	
	@Override
	public User getUserBySystemId(String systemId) throws APIException {
		return rcsDao.getUserBySystemId(systemId);
	}
}
