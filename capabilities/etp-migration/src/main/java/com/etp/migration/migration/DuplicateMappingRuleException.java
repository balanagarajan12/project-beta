package com.etp.migration.migration;

public class DuplicateMappingRuleException extends RuntimeException {

	public DuplicateMappingRuleException(Integer sequenceNo) {
		super("Mapping rule already exists for sequence number " + sequenceNo);
	}
}
