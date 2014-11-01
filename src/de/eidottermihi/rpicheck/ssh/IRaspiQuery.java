package de.eidottermihi.rpicheck.ssh;

import java.util.List;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;

public interface IRaspiQuery {

	/**
	 * Connect to the given host (on port 22).
	 * 
	 * @param host
	 *            the host (e.g. '192.168.2.144' or 'pi.example.com')
	 * @return a {@link SSHClient}
	 * @throws RaspiQueryException
	 */
	SSHClient connect(String host) throws RaspiQueryException;

	/**
	 * Connect to the given host on the given port.
	 * 
	 * @param host
	 *            the host (e.g. '192.168.2.144' or 'pi.example.com')
	 * @return a {@link SSHClient}
	 * @throws RaspiQueryException
	 */
	SSHClient connect(String host, int port) throws RaspiQueryException;

	/**
	 * Authenticate a user on a {@link SSHClient} with specified password.
	 * 
	 * @param client
	 *            the client
	 * @param user
	 *            the user
	 * @param password
	 *            the password
	 * @return an authenticated session
	 * @throws RaspiQueryException
	 */
	SSHClient auth(SSHClient client, String user, String password)
			throws RaspiQueryException;

	/**
	 * Authenticate a user on a {@link SSHClient} with specified private key.
	 * 
	 * @param client
	 *            the client
	 * @param user
	 *            the user
	 * @param privateKey
	 *            the location of the private key (e.g. '/sdcard/mykey.key')
	 * @return an authenticated session
	 * @throws RaspiQueryException
	 */
	SSHClient authKeys(SSHClient client, String user, String privateKey)
			throws RaspiQueryException;

	/**
	 * Authenticate a user on a {@link SSHClient} with specified private key and
	 * given private key password.
	 * 
	 * @param client
	 *            the client
	 * @param user
	 *            the user
	 * @param privateKey
	 *            the location of the private key (e.g. '/sdcard/mykey.key')
	 * @param password
	 *            the password for the private key file
	 * @return an authenticated session
	 * @throws RaspiQueryException
	 */
	SSHClient authKeys(SSHClient client, String user, String privateKey,
			String password) throws RaspiQueryException;

	/**
	 * Start a new Session on the {@link SSHClient}.
	 * 
	 * @param client
	 *            the client (must be connected!)
	 * @return a {@link Session}
	 * @throws RaspiQueryException
	 */
	Session startSession(SSHClient client) throws RaspiQueryException;

	/**
	 * Query the given sessions.
	 * 
	 * @param session
	 *            the session
	 * @param queries
	 *            the queries (commands)
	 * @return the result of the queries
	 * @throws RaspiQueryException
	 */
	List<QueryResult> query(Session session, List<Query> queries)
			throws RaspiQueryException;

}
