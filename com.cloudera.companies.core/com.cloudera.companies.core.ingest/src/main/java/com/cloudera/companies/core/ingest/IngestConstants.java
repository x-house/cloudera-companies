package com.cloudera.companies.core.ingest;

public class IngestConstants {

	public enum Counter {
		FILES_DATASETS, FILES_VALID, FILES_PARTIAL, FILES_UNKNOWN, FILES_CLEANED,
		FILES_PROCCESSED_SUCCESS, FILES_PROCCESSED_SKIP, FILES_PROCCESSED_FAILURE,
		RECORDS_COUNT, RECORDS_PROCESSED_VALID, RECORDS_PROCESSED_MALFORMED, RECORDS_PROCESSED_MALFORMED_KEY, RECORDS_PROCESSED_MALFORMED_DUPLICATE
	};

}
