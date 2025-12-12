package org.openmrs.module.esaudefeatures.api.dao;

import org.hibernate.criterion.Restrictions;
import org.openmrs.User;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository("esaudefeatures.RCSDao")
public class RCSDao {
	
	@Autowired
	DbSessionFactory sessionFactory;
	
	@SuppressWarnings("unchecked")
	public User getUserBySystemId(String systemId) {
		List<User> results = sessionFactory.getCurrentSession().createCriteria(User.class)
		        .add(Restrictions.eq("systemId", systemId)).setMaxResults(1).list();
		
		return results.isEmpty() ? null : results.get(0);
	}
	
	@SuppressWarnings("unchecked")
	public User getUserByEmail(String email) {
		List<User> results = sessionFactory.getCurrentSession().createCriteria(User.class)
		        .add(Restrictions.eq("email", email)).setMaxResults(1).list();
		
		return results.isEmpty() ? null : results.get(0);
	}
}
