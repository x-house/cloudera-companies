package com.cloudera.companies.core.common;

import java.util.Map.Entry;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.util.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class CompaniesDriver extends Configured implements Tool {

	private static Logger log = LoggerFactory.getLogger(CompaniesDriver.class);

	public static final int RETURN_SUCCESS = 0;
	public static final int RETURN_FAILURE_MISSING_ARGS = 1;
	public static final int RETURN_FAILURE_INVALID_ARGS = 2;
	public static final int RETURN_FAILURE_RUNTIME = 3;

	public static final String CONF_MR_FILECOMMITTER_MARK_SUUCESSFUL = "mapreduce.fileoutputcommitter.marksuccessfuljobs";
	public static final String CONF_MR_FILECOMMITTER_SUCCEEDED_FILE_NAME = "_SUCCESS";

	@Override
	public void setConf(Configuration conf) {
		if (log.isDebugEnabled() && conf != null) {
			log.debug("Driver [" + this.getClass().getCanonicalName() + "] initialised with configuration properties:");
			for (Entry<String, String> entry : conf)
				if (log.isDebugEnabled())
					log.debug("\t" + entry.getKey() + "=" + entry.getValue());
		}
		super.setConf(conf);
	}

	public void assertConfig() throws ConfigurationException {
		if (!getConf().getBoolean(CompaniesDriver.CONF_MR_FILECOMMITTER_MARK_SUUCESSFUL, true)) {
			throw new ConfigurationException("Configuraiton property ["
					+ CompaniesDriver.CONF_MR_FILECOMMITTER_MARK_SUUCESSFUL
					+ "] should be set to [true] in order for chained ingest operations to perform correctly");
		}
	}
}
