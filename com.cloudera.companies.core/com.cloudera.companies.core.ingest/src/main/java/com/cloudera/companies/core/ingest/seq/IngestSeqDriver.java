package com.cloudera.companies.core.ingest.seq;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.RunJar;
import org.apache.hadoop.util.ShutdownHookManager;
import org.apache.hadoop.util.ToolRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.companies.core.common.CompaniesDriver;
import com.cloudera.companies.core.common.hdfs.HDFSClientUtil;
import com.cloudera.companies.core.ingest.zip.IngestZipDriver;

public class IngestSeqDriver extends CompaniesDriver {

	private static Logger log = LoggerFactory.getLogger(IngestSeqDriver.class);

	public static enum RecordCounter {
		VALID, MALFORMED, MALFORMED_KEY, MALFORMED_DUPLICATE
	}

	private static AtomicBoolean jobSubmitted = new AtomicBoolean(false);

	@Override
	public int run(String[] args) throws Exception {

		long time = System.currentTimeMillis();

		jobSubmitted.set(false);

		if (args == null || args.length != 2) {
			if (log.isErrorEnabled()) {
				log.error("Usage: " + IngestZipDriver.class.getSimpleName()
						+ " [generic options] <hdfs-input-dir> <hdfs-output-dir>");
				ByteArrayOutputStream byteArrayPrintStream = new ByteArrayOutputStream();
				PrintStream printStream = new PrintStream(byteArrayPrintStream);
				ToolRunner.printGenericCommandUsage(printStream);
				log.error(byteArrayPrintStream.toString());
				printStream.close();
			}
			return CompaniesDriver.RETURN_FAILURE_MISSING_ARGS;
		}

		final FileSystem hdfs = FileSystem.get(getConf());

		String hdfsInputDirPath = args[0];
		Path hdfsInputtDir = new Path(hdfsInputDirPath);
		if (!hdfs.exists(hdfsInputtDir)) {
			if (log.isErrorEnabled()) {
				log.error("HDFS input directory [" + hdfsInputDirPath + "] does not exist");
			}
			return CompaniesDriver.RETURN_FAILURE_INVALID_ARGS;
		}
		if (!hdfs.isDirectory(hdfsInputtDir)) {
			if (log.isErrorEnabled()) {
				log.error("HDFS input directory [" + hdfsInputDirPath + "] is of incorrect type");
			}
			return CompaniesDriver.RETURN_FAILURE_INVALID_ARGS;
		}
		if (!HDFSClientUtil.canPerformAction(hdfs, UserGroupInformation.getCurrentUser().getUserName(),
				UserGroupInformation.getCurrentUser().getGroupNames(), hdfsInputtDir, FsAction.READ_EXECUTE)) {
			if (log.isErrorEnabled()) {
				log.error("HDFS input directory [" + hdfsInputDirPath
						+ "] has too restrictive permissions to read as user ["
						+ UserGroupInformation.getCurrentUser().getUserName() + "]");
			}
			return CompaniesDriver.RETURN_FAILURE_INVALID_ARGS;
		}
		if (log.isInfoEnabled()) {
			log.info("HDFS output directory [" + hdfsInputDirPath + "] validated as [" + hdfsInputtDir + "]");
		}

		String hdfsOutputDirPath = args[1];
		Path hdfsOutputDir = new Path(hdfsOutputDirPath);
		if (hdfs.exists(hdfsOutputDir)) {
			if (log.isErrorEnabled()) {
				log.error("HDFS output directory [" + hdfsOutputDirPath + "] already exists");
			}
			return CompaniesDriver.RETURN_FAILURE_INVALID_ARGS;
		}
		if (!HDFSClientUtil.canPerformAction(hdfs, UserGroupInformation.getCurrentUser().getUserName(),
				UserGroupInformation.getCurrentUser().getGroupNames(), hdfsOutputDir.getParent(), FsAction.ALL)) {
			if (log.isErrorEnabled()) {
				log.error("HDFS parent of output directory [" + hdfsOutputDirPath
						+ "] has too restrictive permissions to write as user ["
						+ UserGroupInformation.getCurrentUser().getUserName() + "]");
			}
			return CompaniesDriver.RETURN_FAILURE_INVALID_ARGS;
		}
		if (log.isInfoEnabled()) {
			log.info("HDFS output directory [" + hdfsOutputDirPath + "] validated as [" + hdfsOutputDir + "]");
		}

		try {
			assertConfig();
		} catch (ConfigurationException exception) {
			if (log.isErrorEnabled()) {
				log.error("Invlaid confuration to run job", exception);
			}
			return CompaniesDriver.RETURN_FAILURE_INVALID_ARGS;
		}

		hdfs.close();

		Configuration conf = new Configuration();
		Job job = new Job(conf);

		job.setJobName(getClass().getSimpleName());

		job.setMapOutputKeyClass(Text.class);
		job.setMapOutputValueClass(Text.class);

		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(Text.class);

		job.setMapperClass(IngestSeqMapper.class);
		job.setReducerClass(IngestSeqReducer.class);

		job.setNumReduceTasks(1);

		job.setInputFormatClass(TextInputFormat.class);
		job.setOutputFormatClass(TextOutputFormat.class);

		FileInputFormat.setInputPaths(job, hdfsInputtDir);
		FileOutputFormat.setOutputPath(job, hdfsOutputDir);

		job.setJarByClass(IngestSeqDriver.class);

		if (log.isInfoEnabled()) {
			log.info("File ingest ETL job about to be submitted");
		}

		jobSubmitted.set(true);

		int exitCode = job.waitForCompletion(true) ? RETURN_SUCCESS : RETURN_FAILURE_RUNTIME;

		if (exitCode == RETURN_SUCCESS) {
			if (log.isInfoEnabled()) {
				log.info("File ingest ETL complete in [" + (System.currentTimeMillis() - time) + "] ms");
			}
		} else {
			if (log.isInfoEnabled()) {
				log.info("File ingest ETL failed in [" + (System.currentTimeMillis() - time) + "] ms");
			}
		}

		return exitCode;
	}

	public static void main(String[] args) throws Exception {
		ShutdownHookManager.get().addShutdownHook(new Runnable() {
			@Override
			public void run() {
				if (jobSubmitted.get()) {
					if (log.isErrorEnabled()) {
						log.error("Halting before job completion, job submitted and will continue in the background");
					}
				} else {
					if (log.isErrorEnabled()) {
						log.error("Halting before job submitted");
					}
				}
			}
		}, RunJar.SHUTDOWN_HOOK_PRIORITY + 1);
		System.exit(ToolRunner.run(new IngestSeqDriver(), args));
	}
}
