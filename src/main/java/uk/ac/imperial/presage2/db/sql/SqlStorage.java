/**
 * 	Copyright (C) 2011-2012 Sam Macbeth <sm1106 [at] imperial [dot] ac [dot] uk>
 *
 * 	This file is part of Presage2.
 *
 *     Presage2 is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Presage2 is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser Public License
 *     along with Presage2.  If not, see <http://www.gnu.org/licenses/>.
 */
package uk.ac.imperial.presage2.db.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Logger;

import uk.ac.imperial.presage2.core.TimeDriven;
import uk.ac.imperial.presage2.core.db.DatabaseService;
import uk.ac.imperial.presage2.core.db.StorageService;
import uk.ac.imperial.presage2.core.db.persistent.PersistentAgent;
import uk.ac.imperial.presage2.core.db.persistent.PersistentSimulation;
import uk.ac.imperial.presage2.core.db.persistent.TransientAgentState;
import uk.ac.imperial.presage2.core.simulator.Scenario;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

@Singleton
public class SqlStorage implements StorageService, DatabaseService, TimeDriven,
		Provider<Connection> {

	protected final Logger logger = Logger.getLogger(SqlStorage.class);
	protected Properties jdbcInfo;
	protected Connection conn = null;
	protected long simId = -1;

	Map<Long, Simulation> simulations = new HashMap<Long, Simulation>();

	Timer syncTimer;

	protected Set<Simulation> simulationQ = Collections
			.synchronizedSet(new HashSet<Simulation>());
	protected Set<Environment> environmentQ = Collections
			.synchronizedSet(new HashSet<Environment>());
	protected Set<Environment> environmentTransientQ = Collections
			.synchronizedSet(new HashSet<Environment>());
	protected Set<Agent> agentQ = Collections
			.synchronizedSet(new HashSet<Agent>());
	protected Set<Agent> agentTransientQ = Collections
			.synchronizedSet(new HashSet<Agent>());

	protected BlockingQueue<PreparedStatement> batchQueryQ = new LinkedBlockingQueue<PreparedStatement>(
			20);
	Thread queryExecutor;

	boolean timeDriven = false;

	@Inject
	public SqlStorage(@Named(value = "sql.info") Properties jdbcInfo) {
		super();
		this.jdbcInfo = jdbcInfo;
		if (jdbcInfo.getProperty("driver", "").equalsIgnoreCase(
				"org.postgresql.Driver"))
			Sql.dialect = Dialect.POSTGRESQL;
		else
			Sql.dialect = Dialect.MYSQL;
	}

	@Override
	public void start() throws Exception {
		if (conn == null) {
			try {
				Class.forName(jdbcInfo.getProperty("driver",
						"com.mysql.jdbc.Driver"));
				this.conn = DriverManager.getConnection(
						jdbcInfo.getProperty("url"), jdbcInfo);
			} catch (SQLException e) {
				logger.fatal(
						"Exception while attempting to connect to jdbc db.", e);
				throw e;
			} catch (ClassNotFoundException e) {
				logger.fatal("JDBC driver not found.", e);
				throw e;
			}
			initTables();
			// start batch thread
			queryExecutor = new Thread(new BatchQueryExecutor(),
					"Query executor");
			queryExecutor.start();
			if (!timeDriven) {
				startUpdater();
			}
		}
	}

	class BatchQueryExecutor implements Runnable {
		@Override
		public void run() {
			while (true) {
				List<PreparedStatement> tx = new ArrayList<PreparedStatement>();
				boolean breakOnFinish = false;
				try {
					tx.add(batchQueryQ.take());
					batchQueryQ.drainTo(tx);
					conn.setAutoCommit(false);
					for (PreparedStatement stmt : tx) {
						if (stmt.isClosed())
							breakOnFinish = true;
						else {
							logger.debug("Execute: " + stmt);
							stmt.executeBatch();
						}
					}
					conn.commit();
					conn.setAutoCommit(true);
					if (breakOnFinish)
						break;

				} catch (SQLException e) {
					logger.warn("Error executing batch query", e);
					logger.warn("Next exception was: ", e.getNextException());
				} catch (InterruptedException e1) {
				} finally {
					for (PreparedStatement stmt : tx) {
						try {
							stmt.close();
						} catch (SQLException e) {
						}
					}
				}
			}
			logger.debug("shutdown");
		}
	}

	protected void initTables() {
		Statement createSimulations = null;
		Statement createParameters = null;
		try {
			createSimulations = conn.createStatement();
			createSimulations.execute(Sql.createSimulationTable());
			createSimulations.close();
			createParameters = conn.createStatement();
			createParameters.execute(Sql.createParametersTable());
			createParameters.close();
		} catch (SQLException e) {
			logger.fatal("Couldn't create tables", e);
			throw new RuntimeException(e);
		}
	}

	@Inject(optional = true)
	void setScenario(Scenario s) {
		s.addTimeDriven(this);
		timeDriven = true;
		if (syncTimer != null) {
			syncTimer.cancel();
		}
	}

	protected void startUpdater() {
		syncTimer = new Timer();
		syncTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				incrementTime();
			}
		}, 200L, 5000L);
	}

	public void incrementTime() {
		updateSimulations();
		if (timeDriven) {
			updateEnvironment();
			updateTransientEnvironment();
			updateAgents();
			updateTransientAgents();
		}
	}

	PreparedStatement insertParameters = null;
	PreparedStatement updateParameters = null;

	protected synchronized void setParameter(long simId, String key,
			String value) {
		if (insertParameters == null || updateParameters == null) {
			try {
				insertParameters = conn.prepareStatement(Sql
						.insertIntoParameters());
				updateParameters = conn
						.prepareStatement(Sql.updateParameters());
			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
		}

		try {
			switch (Sql.dialect) {
			case POSTGRESQL:
				updateParameters.setString(1, value);
				updateParameters.setLong(2, simId);
				updateParameters.setString(3, key);
				insertParameters.setLong(1, simId);
				insertParameters.setString(2, key);
				insertParameters.setString(3, value);
				insertParameters.setLong(4, simId);
				insertParameters.setString(5, key);

				insertParameters.executeUpdate();
				updateParameters.executeUpdate();
				break;
			case MYSQL:
			default:
				insertParameters.setLong(1, simId);
				insertParameters.setString(2, key);
				insertParameters.setString(3, value);

				insertParameters.executeUpdate();
			}
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	protected void updateSimulations() {
		PreparedStatement updateSimulation = null;

		try {
			updateSimulation = conn.prepareStatement(Sql
					.formatQuery("UPDATE simulations " + "SET `state` = ? ,"
							+ "`currentTime` = ? ," + "`startedAt` = ? ,"
							+ "`finishedAt` = ? ," + "`parent` = ? "
							+ "WHERE `id` = ?" + ""));

		} catch (SQLException e) {
			throw new RuntimeException(e);
		}

		try {
			synchronized (simulationQ) {
				for (Simulation s : simulationQ) {
					updateSimulation.setString(1, s.getState());
					updateSimulation.setInt(2, s.getCurrentTime());
					updateSimulation.setLong(3, s.getStartedAt());
					updateSimulation.setLong(4, s.getFinishedAt());
					updateSimulation.setLong(5, s.parent);
					updateSimulation.setLong(6, s.getID());
					updateSimulation.addBatch();

					s.dirty = false;
				}
				batchQueryQ.put(updateSimulation);
				simulationQ.clear();
			}
		} catch (SQLException e) {
			throw new RuntimeException(e);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	protected void updateEnvironment() {
		logger.info(environmentQ.size() + " environment updates");
		environmentQ.clear();
	}

	protected void updateTransientEnvironment() {
		logger.info(environmentTransientQ.size()
				+ " transient environment updates");
		synchronized (environmentTransientQ) {
			for (Environment e : environmentTransientQ) {
				e.transientProperties.clear();
			}
			environmentTransientQ.clear();
		}
	}

	protected void updateAgents() {
		logger.info(agentQ.size() + " agent updates");
		agentQ.clear();
	}

	protected void updateTransientAgents() {
		logger.info(agentTransientQ.size() + " transient agent updates");
		synchronized (agentTransientQ) {
			for (Agent a : agentTransientQ) {
				a.transientProperties.clear();
			}
		}
	}

	@Override
	public boolean isStarted() {
		return conn != null;
	}

	@Override
	public void stop() {
		if (conn != null) {
			if (!timeDriven) {
				syncTimer.cancel();
			}
			incrementTime();
			try {
				PreparedStatement queuePoison = conn
						.prepareStatement("SELECT 1");
				queuePoison.close();
				batchQueryQ.put(queuePoison);

				queryExecutor.join();
			} catch (SQLException e2) {
				throw new RuntimeException(e2);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			if (timeDriven)
				onComplete();
			try {
				conn.close();
				conn = null;
			} catch (SQLException e) {
				logger.warn("Exception when closing db.", e);
			}
		}
	}

	protected void onComplete() {
	}

	public long getSimId() {
		return simId;
	}

	protected void setSimId(long simId) {
		this.simId = simId;
	}

	@Override
	public PersistentSimulation createSimulation(String name, String classname,
			String state, int finishTime) {
		PreparedStatement createSimulation = null;
		ResultSet generatedKeys = null;
		try {
			createSimulation = conn.prepareStatement(
					Sql.insertIntoSimulations(),
					Statement.RETURN_GENERATED_KEYS);
			createSimulation.setString(1, name);
			createSimulation.setString(2, state);
			createSimulation.setString(3, classname);
			createSimulation.setInt(4, finishTime);
			createSimulation.setLong(5, new Date().getTime());
			createSimulation.executeUpdate();
			generatedKeys = createSimulation.getGeneratedKeys();
			if (generatedKeys.next()) {
				long simId = generatedKeys.getLong(1);
				Simulation s = new Simulation(simId, name, classname, state,
						finishTime, this);
				this.simulations.put(simId, s);
				this.setSimId(simId);
				return s;
			}
		} catch (SQLException e) {
			throw new RuntimeException(e);
		} finally {
			if (generatedKeys != null)
				try {
					generatedKeys.close();
				} catch (SQLException e) {
				}
			if (createSimulation != null)
				try {
					createSimulation.close();
				} catch (SQLException e) {
				}
		}
		return null;
	}

	@Override
	public PersistentSimulation getSimulation() {
		if (this.simId >= 0)
			return getSimulationById(simId);
		return null;
	}

	@Override
	public PersistentSimulation getSimulationById(long id) {
		if (simulations.containsKey(id)) {
			return simulations.get(id);
		} else {
			PreparedStatement getSimulation = null;
			PreparedStatement getParameters = null;
			ResultSet simRow = null;
			ResultSet paramsRow = null;
			try {
				getSimulation = conn
						.prepareStatement(Sql
								.formatQuery("SELECT `id`, `name`, `state`, `classname`, `currentTime`, `finishTime`, "
										+ "`createdAt`, "
										+ "`startedAt`, "
										+ "`finishedAt`, `parent` "
										+ "FROM simulations WHERE `id` = ?"));
				getParameters = conn.prepareStatement(Sql.getParametersById());

				getSimulation.setLong(1, id);
				simRow = getSimulation.executeQuery();
				if (simRow.next()) {
					Simulation s = new Simulation(simRow, this);
					getParameters.setLong(1, s.getID());
					paramsRow = getParameters.executeQuery();
					s.setParameters(paramsRow);
					simulations.put(s.getID(), s);
					return s;
				}
			} catch (SQLException e) {
				throw new RuntimeException(e);
			} finally {
				if (getSimulation != null) {
					try {
						getSimulation.close();
					} catch (SQLException e) {
					}
				}
				if (simRow != null) {
					try {
						simRow.close();
					} catch (SQLException e) {
					}
				}
				if (getParameters != null) {
					try {
						getParameters.close();
					} catch (SQLException e) {
					}
				}
				if (paramsRow != null) {
					try {
						paramsRow.close();
					} catch (SQLException e) {
					}
				}
			}
			return null;
		}
	}

	@Override
	public List<Long> getSimulations() {
		PreparedStatement getSimulations = null;
		ResultSet simRow = null;
		PreparedStatement getParameters = null;
		ResultSet paramsRow = null;
		List<Long> simIds = new LinkedList<Long>();
		try {
			getSimulations = conn.prepareStatement(Sql.getSimulations());
			getParameters = conn.prepareStatement(Sql.getParametersById());

			simRow = getSimulations.executeQuery();
			while (simRow.next()) {
				Simulation s = new Simulation(simRow, this);
				getParameters.setLong(1, s.getID());
				paramsRow = getParameters.executeQuery();
				s.setParameters(paramsRow);
				simulations.put(s.getID(), s);
				simIds.add(s.getID());
			}
		} catch (SQLException e) {
			throw new RuntimeException(e);
		} finally {
			if (getSimulations != null) {
				try {
					getSimulations.close();
				} catch (SQLException e) {
				}
			}
			if (simRow != null) {
				try {
					simRow.close();
				} catch (SQLException e) {
				}
			}
		}
		return simIds;
	}

	@Override
	public void setSimulation(PersistentSimulation sim) {
		if (sim != null)
			this.simId = sim.getID();
		else
			this.simId = -1;
	}

	@Override
	public PersistentAgent createAgent(UUID agentID, String name) {
		if (this.simId >= 0) {
			Simulation s = simulations.get(this.simId);
			if (s != null) {
				Agent a = new Agent(agentID, name, this);
				s.agents.add(a);
				return a;
			}
		}
		throw new RuntimeException();
	}

	@Override
	public PersistentAgent getAgent(UUID agentID) {
		if (this.simId >= 0) {
			Simulation s = simulations.get(this.simId);
			if (s != null) {
				Set<PersistentAgent> agents = s.getAgents();
				for (PersistentAgent a : agents) {
					if (a.getID().equals(agentID))
						return a;
				}
			}
		}
		throw new RuntimeException();
	}

	@Override
	public TransientAgentState getAgentState(UUID agentID, int time) {
		PersistentAgent a = getAgent(agentID);
		if (a != null)
			return a.getState(time);
		return null;
	}

	List<Long> getChildren(long simId) {
		// wait for empty query queue to ensure changes have been committed to
		// db.
		logger.debug("getChildren wait");
		updateSimulations();
		while (!batchQueryQ.isEmpty()) {
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
			}
		}
		logger.debug("getChildren continue");
		List<Long> simIds = new LinkedList<Long>();
		PreparedStatement getChildren = null;
		ResultSet simRow = null;
		try {
			getChildren = conn
					.prepareStatement(Sql
							.formatQuery("SELECT `id`, `name`, `state`, `classname`, `currentTime`, `finishTime`, "
									+ "`createdAt`, "
									+ "`startedAt`, "
									+ "`finishedAt`, `parent` "
									+ "FROM simulations WHERE `parent` = ?"));
			getChildren.setLong(1, simId);
			simRow = getChildren.executeQuery();
			while (simRow.next()) {
				Simulation s = new Simulation(simRow, this);
				simulations.put(s.getID(), s);
				simIds.add(s.getID());
			}
		} catch (SQLException e) {
			new RuntimeException(e);
		} finally {
			if (getChildren != null) {
				try {
					getChildren.close();
				} catch (SQLException e) {
				}
			}
			if (simRow != null) {
				try {
					simRow.close();
				} catch (SQLException e) {
				}
			}
		}
		return simIds;
	}

	@Override
	public Connection get() {
		if (!isStarted()) {
			try {
				start();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		return this.conn;
	}

	/**
	 * Signals that data retrieval is taking place for this simulation and state
	 * data should be retrieved from storage to facilitate this. (optional
	 * operation)
	 * 
	 * @param simId
	 *            ID of simulation data to load.
	 */
	protected void fetchData(long simId) {

	}

}
