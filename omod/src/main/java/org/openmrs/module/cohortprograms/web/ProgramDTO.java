package org.openmrs.module.cohortprograms.web;

import org.openmrs.Cohort;
import org.openmrs.Program;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * @uthor Willa Mhawila<a.mhawila@gmail.com> on 4/6/21.
 */
public class ProgramDTO extends Program {
	
	private Program program;
	
	private Set<Cohort> cohorts = new LinkedHashSet<Cohort>();
	
	public ProgramDTO() {
		super();
	}
	
	public ProgramDTO(Integer programId, Set<Cohort> cohorts) {
		super(programId);
		assert cohorts != null;
		this.cohorts = cohorts;
	}
	
	public ProgramDTO(String name, Set<Cohort> cohorts) {
		super(name);
		assert cohorts != null;
		this.cohorts = cohorts;
	}
	
	public ProgramDTO(Program program) {
		assert program != null;
		this.setProgramId(getProgramId());
		this.setName(program.getName());
		this.setDescription(program.getDescription());
		this.setConcept(program.getConcept());
		this.setAllWorkflows(program.getAllWorkflows());
		this.setDateCreated(program.getDateCreated());
		this.setCreator(program.getCreator());
		this.setChangedBy(program.getChangedBy());
		this.setRetired(program.getRetired());
		this.setRetiredBy(program.getRetiredBy());
		this.setRetireReason(program.getRetireReason());
		this.setDateRetired(program.getDateRetired());
		this.setUuid(program.getUuid());
	}
	
	public ProgramDTO(Program program, Set<Cohort> cohorts) {
		this(program);
		assert cohorts != null;
		this.cohorts = cohorts;
	}
	
	public Program getProgram() {
		if (program == null) {
			program = new Program(this.getProgramId());
		}
		program.setName(this.getName());
		program.setDescription(this.getDescription());
		program.setConcept(this.getConcept());
		program.setAllWorkflows(this.getAllWorkflows());
		program.setDateCreated(this.getDateCreated());
		program.setCreator(this.getCreator());
		program.setChangedBy(this.getChangedBy());
		program.setRetired(this.getRetired());
		program.setRetiredBy(this.getRetiredBy());
		program.setRetireReason(this.getRetireReason());
		program.setDateRetired(this.getDateRetired());
		program.setUuid(this.getUuid());
		return program;
	}
	
	public Set<Cohort> getCohorts() {
		return cohorts;
	}
	
	public void setCohorts(Set<Cohort> cohorts) {
		assert cohorts != null;
		this.cohorts = cohorts;
	}
	
	public void addCohort(Cohort cohort) {
		this.cohorts.add(cohort);
	}
	
	public void addCohorts(Set<Cohort> cohorts) {
		this.cohorts.addAll(cohorts);
	}
}
