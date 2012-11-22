package com.cloudera.companies.core.ingest.seq;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.RunJar;
import org.apache.hadoop.util.ShutdownHookManager;
import org.apache.hadoop.util.ToolRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.companies.core.common.CompaniesDriver;
import com.cloudera.companies.core.common.hdfs.HDFSClientUtil;
import com.cloudera.companies.core.common.mapreduce.CompaniesFileKey;
import com.cloudera.companies.core.common.mapreduce.CompaniesFileKeyCompositeComparator;
import com.cloudera.companies.core.common.mapreduce.CompaniesFileKeyGroupComparator;
import com.cloudera.companies.core.common.mapreduce.CompaniesFileKeyGroupPartitioner;
import com.cloudera.companies.core.common.mapreduce.CompaniesFileZipFileInputFormat;
import com.cloudera.companies.core.ingest.zip.IngestZipDriver;

public class IngestSeqDriver extends CompaniesDriver {

	private static Logger log = LoggerFactory.getLogger(IngestSeqDriver.class);

	public static enum RecordCounter {
		VALID, MALFORMED, MALFORMED_KEY, MALFORMED_DUPLICATE
	}

	public static final String NAMED_OUTPUT_PARTION_SEQ_FILES = "PartionedSequenceFiles";

	private static AtomicBoolean jobSubmitted = new AtomicBoolean(false);

	@Override
	public int runCompaniesDriver(String[] args) throws Exception {

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

		final FileSystem hdfsFileSystem = FileSystem.get(getConf());

		String hdfsInputDirPath = args[0];
		Path hdfsInputDir = new Path(hdfsInputDirPath);
		if (!hdfsFileSystem.exists(hdfsInputDir)) {
			if (log.isErrorEnabled()) {
				log.error("HDFS input directory [" + hdfsInputDirPath + "] does not exist");
			}
			return CompaniesDriver.RETURN_FAILURE_INVALID_ARGS;
		}
		if (!hdfsFileSystem.isDirectory(hdfsInputDir)) {
			if (log.isErrorEnabled()) {
				log.error("HDFS input directory [" + hdfsInputDirPath + "] is of incorrect type");
			}
			return CompaniesDriver.RETURN_FAILURE_INVALID_ARGS;
		}
		if (!HDFSClientUtil.canDoAction(hdfsFileSystem, UserGroupInformation.getCurrentUser().getUserName(),
				UserGroupInformation.getCurrentUser().getGroupNames(), hdfsInputDir, FsAction.READ_EXECUTE)) {
			if (log.isErrorEnabled()) {
				log.error("HDFS input directory [" + hdfsInputDirPath
						+ "] has too restrictive permissions to read as user ["
						+ UserGroupInformation.getCurrentUser().getUserName() + "]");
			}
			return CompaniesDriver.RETURN_FAILURE_INVALID_ARGS;
		}
		if (log.isInfoEnabled()) {
			log.info("HDFS output directory [" + hdfsInputDirPath + "] validated as [" + hdfsInputDir + "]");
		}

		String hdfsOutputDirPath = args[1];
		Path hdfsOutputDir = new Path(hdfsOutputDirPath);
		if (hdfsFileSystem.exists(hdfsOutputDir)) {
			if (log.isErrorEnabled()) {
				log.error("HDFS output directory [" + hdfsOutputDirPath + "] already exists");
			}
			return CompaniesDriver.RETURN_FAILURE_INVALID_ARGS;
		}
		if (!HDFSClientUtil.canDoAction(hdfsFileSystem, UserGroupInformation.getCurrentUser().getUserName(),
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

		Set<Path> hdfsInputDirs = new HashSet<Path>();
		RemoteIterator<LocatedFileStatus> inputFiles = hdfsFileSystem.listFiles(hdfsInputDir, true);
		while (inputFiles.hasNext()) {
			LocatedFileStatus fileStatus = inputFiles.next();
			if (fileStatus.getPath().getName().equals(CONF_MR_FILECOMMITTER_SUCCEEDED_FILE_NAME)) {
				String fileSuccessParent = fileStatus.getPath().getParent().toString();
				String fileSuccessParentSuffix = fileSuccessParent.substring(fileSuccessParent.indexOf(hdfsInputDir
						.toString()) + hdfsInputDir.toString().length());
				if (!hdfsFileSystem.exists(new Path(hdfsOutputDir.toString() + fileSuccessParentSuffix,
						CONF_MR_FILECOMMITTER_SUCCEEDED_FILE_NAME))) {
					hdfsInputDirs.add(fileStatus.getPath().getParent());
				}
			}
		}
		if (hdfsInputDirs.isEmpty()) {
			if (log.isInfoEnabled()) {
				log.info("No suitable files found to ingest");
			}
			return RETURN_SUCCESS;
		}

		hdfsFileSystem.close();

		Job job = new Job(getConf());

		job.setJobName(getClass().getSimpleName());

		job.setPartitionerClass(CompaniesFileKeyGroupPartitioner.class);
		job.setGroupingComparatorClass(CompaniesFileKeyGroupComparator.class);
		job.setSortComparatorClass(CompaniesFileKeyCompositeComparator.class);

		job.setMapOutputKeyClass(CompaniesFileKey.class);
		job.setMapOutputValueClass(Text.class);

		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(Text.class);

		job.setMapperClass(IngestSeqMapper.class);
		job.setReducerClass(IngestSeqReducer.class);

		job.setInputFormatClass(CompaniesFileZipFileInputFormat.class);
		job.setOutputFormatClass(TextOutputFormat.class);

		FileInputFormat.setInputPaths(job, hdfsInputDirs.toArray(new Path[hdfsInputDirs.size()]));
		FileOutputFormat.setOutputPath(job, hdfsOutputDir);

		MultipleOutputs.addNamedOutput(job, NAMED_OUTPUT_PARTION_SEQ_FILES, SequenceFileOutputFormat.class, Text.class,
				Text.class);

		job.setJarByClass(IngestSeqDriver.class);

		if (log.isInfoEnabled()) {
			log.info("Sequence file ingest job about to be submitted");
		}

		jobSubmitted.set(true);

		int exitCode = job.waitForCompletion(true) ? RETURN_SUCCESS : RETURN_FAILURE_RUNTIME;

		if (exitCode == RETURN_SUCCESS) {
			if (log.isInfoEnabled()) {
				log.info("Sequence file ingest complete in [" + (System.currentTimeMillis() - time) + "] ms");
			}
		} else {
			if (log.isInfoEnabled()) {
				log.info("Sequence file ingest failed in [" + (System.currentTimeMillis() - time) + "] ms");
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
