package de.foellix.aql.converter.dialdroid;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException;
import com.mysql.jdbc.jdbc2.optional.MysqlDataSource;

import de.foellix.aql.Log;
import de.foellix.aql.converter.IConverter;
import de.foellix.aql.datastructure.Answer;
import de.foellix.aql.datastructure.Flow;
import de.foellix.aql.datastructure.Flows;
import de.foellix.aql.datastructure.KeywordsAndConstants;
import de.foellix.aql.datastructure.QuestionPart;
import de.foellix.aql.datastructure.Reference;
import de.foellix.aql.helper.HashHelper;
import de.foellix.aql.helper.Helper;
import de.foellix.aql.system.task.ToolTaskInfo;

public class ConverterDIALDroid implements IConverter {
	private static final File DATABASE_PROPERTIES = new File("data/converter/dialdroid_config.properties");
	private List<String> logLines;

	@Override
	public Answer parse(final File resultFile, final ToolTaskInfo taskInfo)
			throws MySQLIntegrityConstraintViolationException {
		final QuestionPart question = taskInfo.getQuestion();

		try (BufferedReader br = new BufferedReader(new FileReader(resultFile))) {
			this.logLines = new ArrayList<>();
			String line = "";
			while ((line = br.readLine()) != null) {
				if (line.contains(
						"com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException: Cannot add or update a child row: a foreign key constraint fails")) {
					throw new MySQLIntegrityConstraintViolationException(line);
				}
				if (line.contains(" (in ")) {
					this.logLines.add(line);
				}
			}
		} catch (final IOException e) {
			Log.error("Could not read log file: " + resultFile.getAbsolutePath());
		}

		final Answer answer = new Answer();
		final Flows flows = new Flows();

		final MysqlDataSource dataSource = new MysqlDataSource();
		try {
			final java.util.Properties prop = new java.util.Properties();
			final FileInputStream in = new FileInputStream(DATABASE_PROPERTIES);
			prop.load(in);
			in.close();

			dataSource.setUser(prop.getProperty("user"));
			if (prop.getProperty("password") != null && !prop.getProperty("password").equals("")
					&& !prop.getProperty("password").equals(" ")) {
				dataSource.setPassword(prop.getProperty("password"));
			}
			dataSource.setServerName(prop.getProperty("server"));
			dataSource.setDatabaseName(prop.getProperty("database"));
		} catch (final IOException e) {
			Log.error("Could not read DIALDroid-Converter's config file: " + DATABASE_PROPERTIES.getAbsolutePath());
		}

		try {
			final Connection conn = dataSource.getConnection();
			final Statement stmt = conn.createStatement();
			final String query = "SELECT ICCEntryLeaks.leak_sink AS toStatement, ICCExitLeaks.leak_source AS fromStatement, EntryPoints.method AS toMethod, ExitPoints.method AS fromMethod FROM SensitiveChannels JOIN Applications Applications_A ON Applications_A.id = SensitiveChannels.fromapp JOIN Applications Applications_B ON Applications_B.id = SensitiveChannels.toapp JOIN ExitPoints ON SensitiveChannels.exitpoint = ExitPoints.id JOIN ICCExitLeaks ON SensitiveChannels.exitpoint = ICCExitLeaks.exit_point_id JOIN EntryPoints ON SensitiveChannels.entryclass = EntryPoints.class_id JOIN ICCEntryLeaks ON EntryPoints.id = ICCEntryLeaks.entry_point_id WHERE Applications_A.shasum = '"
					+ HashHelper
							.getHash(question.getAllReferences().get(0).getApp().getHashes(),
									KeywordsAndConstants.HASH_TYPE_SHA256)
							.toUpperCase()
					+ "' AND Applications_B.shasum = '"
					+ (question.getAllReferences().size() > 1
							? HashHelper.getHash(question.getAllReferences().get(1).getApp().getHashes(),
									KeywordsAndConstants.HASH_TYPE_SHA256).toUpperCase()
							: HashHelper.getHash(question.getAllReferences().get(0).getApp().getHashes(),
									KeywordsAndConstants.HASH_TYPE_SHA256).toUpperCase())
					+ "' GROUP BY CONCAT(toStatement, fromStatement, toMethod, fromMethod)";

			Log.msg(query, Log.DEBUG_DETAILED);

			final ResultSet rs = stmt.executeQuery(query);

			while (rs.next()) {
				final Reference from = new Reference(), to = new Reference();
				from.setStatement(Helper.createStatement(rs.getString("fromStatement")));
				from.setMethod(searchMethod(from.getStatement().getStatementfull()));
				from.setClassname(Helper.cut(from.getMethod(), "<", ": "));
				from.setApp(question.getAllReferences().get(0).getApp());
				from.setType(KeywordsAndConstants.REFERENCE_TYPE_FROM);

				to.setStatement(Helper.createStatement(rs.getString("toStatement")));
				to.setMethod(rs.getString("toMethod"));
				to.setClassname(Helper.cut(rs.getString("toMethod"), "<", ": "));
				to.setApp((question.getAllReferences().size() > 1 ? question.getAllReferences().get(1).getApp()
						: question.getAllReferences().get(0).getApp()));
				to.setType(KeywordsAndConstants.REFERENCE_TYPE_TO);

				final Flow flow = new Flow();
				flow.getReference().add(from);
				flow.getReference().add(to);

				flows.getFlow().add(flow);
			}

			rs.close();
			stmt.close();
			conn.close();
		} catch (final Exception e) {
			Log.error("An error occurred while accessing the DIALDroid database: " + e.getMessage());
		}

		if (!flows.getFlow().isEmpty()) {
			answer.setFlows(flows);
		}

		return answer;
	}

	private String searchMethod(String statement) {
		String method = null;
		try {
			for (final String line : this.logLines) {
				if (line.contains(statement) && line.contains(" (in ")) {
					final String temp = Helper.cutFromFirstToLast(line, " (in ", ")");
					if (method != null && !temp.equals(method)) {
						return null;
					} else if (method == null) {
						method = temp;
					}
				}
			}
		} catch (final Exception e) {
			e.printStackTrace();
		}
		return method;
	}
}
