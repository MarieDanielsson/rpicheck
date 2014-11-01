package de.eidottermihi.rpicheck.ssh.tests;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;

import org.junit.Test;

import de.eidottermihi.rpicheck.ssh.Query;
import de.eidottermihi.rpicheck.ssh.RaspiQuery;
import de.eidottermihi.rpicheck.ssh.RaspiQueryException;

public class RaspiQueryTest {

	@Test
	public void full_query() throws RaspiQueryException {
		RaspiQuery q = new RaspiQuery("192.168.0.4", "root", 22);
		q.connect("openelec");
		q.queryCpuSerial();
		q.queryDiskUsage();
		q.queryDistributionName();
		q.queryMemoryInformation();
		q.queryNetworkInformation();
		q.queryProcesses(true);
		q.queryUptime();
		q.queryVcgencmd();
		q.disconnect();
	}

	@Test
	public void full_query_in_one() throws RaspiQueryException, IOException {
		RaspiQuery q = new RaspiQuery("192.168.0.4", "root", 22);
		SSHClient client = q.connect("192.168.0.5");
		q.auth(client, "root", "openelec");
		Session session = q.startSession(client);
		List<Query> queries = new ArrayList<Query>();
		Query ls = new Query();
		ls.setId("ls");
		ls.setCommand("ls -l");
		Query pwd = new Query();
		pwd.setId("pwd");
		pwd.setCommand("pwd");
		Query crap = new Query();
		crap.setId("crap");
		crap.setCommand("fasfas");
		queries.add(ls);
		queries.add(pwd);
		queries.add(crap);
		q.query(session, queries);
		client.disconnect();
	}
}
