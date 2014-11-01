package de.eidottermihi.rpicheck.ssh;

public class QueryResult extends BaseQuery {
	private String rawOutput;

	public String getRawOutput() {
		return rawOutput;
	}

	public void setRawOutput(String rawOutput) {
		this.rawOutput = rawOutput;
	}
}
