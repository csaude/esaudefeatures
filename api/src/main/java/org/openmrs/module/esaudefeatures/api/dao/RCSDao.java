package org.openmrs.module.esaudefeatures.api.dao;

import org.hibernate.criterion.Restrictions;
import org.openmrs.User;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository("esaudefeatures.RCSDao")
public class RCSDao {
	
	@Autowired
	DbSessionFactory sessionFactory;
	
	public User getUserBySystemId(String systemId) {
		return (User) sessionFactory.getCurrentSession().createCriteria(User.class)
		        .add(Restrictions.eq("systemId", systemId)).uniqueResult();
	}
	
	public User getUserByEmail(String email) {
		return (User) sessionFactory.getCurrentSession().createCriteria(User.class).add(Restrictions.eq("email", email))
		        .uniqueResult();
	}
}
