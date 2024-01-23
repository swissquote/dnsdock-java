package org.swissquote.dnsdock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.ExtendedFlags;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Header;
import org.xbill.DNS.Message;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.OPTRecord;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.RRset;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.SetResponse;
import org.xbill.DNS.TSIG;
import org.xbill.DNS.TSIGRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;
import org.xbill.DNS.Zone;

public class DnsDockJava {

	public static final String DOCKER_DOMAIN = ".docker";
	private static final int FLAG_DNSSECOK = 1;
	private static final int FLAG_SIGONLY = 2;
	private static final Name DNSDOCK_JAVA = getName("dnsdock-java.");
	private static final Name DNSDOCK_JAVA_EMAIL = getName("dnssqdock@swissquote.ch.");
	private static final Logger LOG = LoggerFactory.getLogger(DnsDockJava.class);
	private final Map<Name, TSIG> TSIGs = new HashMap<>();
	private final DockerHostsRegistry dockerHostsRegistry = new DockerHostsRegistry();
	private final AtomicBoolean running = new AtomicBoolean(false);
	private Thread serverThread;

	public static void main(String[] args) throws Exception {
		DnsDockJava dnsDock = new DnsDockJava();
		InetAddress bindAddress = args.length == 0 ? InetAddress.getLocalHost() : InetAddress.getByName(args[0]);
		Thread serverThread = new Thread(() -> {
			Runtime.getRuntime().addShutdownHook(new Thread(dnsDock::stop));
			try {
				dnsDock.start(bindAddress);
			}
			catch (Exception e) {
				LOG.error("Failed to start DNSDock java", e);
			}
		}, "DNSDock-upd-server");
		serverThread.setDaemon(false);
		serverThread.start();
	}

	private static Name getName(String name) {
		try {
			return Name.fromString(name);
		}
		catch (TextParseException e) {
			throw new IllegalStateException(e);
		}
	}

	public void stop() {
		LOG.info("Stopping DNSDock java");
		running.set(false);
		try {
			serverThread.join();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public void start(InetAddress bindAddress) throws Exception {
		serverThread = Thread.currentThread();
		int port = 53;
		LOG.info("Starting DNSDock java on {}:{}", bindAddress, port);
		DatagramSocket socket = new DatagramSocket(port, bindAddress);
		socket.setSoTimeout(1000);
		LOG.info("DNSDock java running on {}:{}", bindAddress, port);
		running.set(true);
		while (running.get()) {
			byte[] receiveData = new byte[1024];
			DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
			try {
				socket.receive(receivePacket);
			}
			catch (SocketTimeoutException ex) {
				// read timeout
				continue;
			}
			catch (IOException ex) {
				LOG.error("Failed to read packet", ex);
				continue;
			}
			byte[] packetData = new byte[receivePacket.getLength()];
			System.arraycopy(receivePacket.getData(), 0, packetData, 0, receivePacket.getLength());
			try {
				Message query = new Message(packetData);
				LOG.info("Received DNS query\n{}", query);
				if (query.getRcode() == Opcode.QUERY) {
					Message response = getQueryResponse(query, packetData);
					LOG.info("Sending DNS response\n{}", response);
					InetAddress clientAddress = receivePacket.getAddress();
					int clientPort = receivePacket.getPort();
					byte[] responseData = response.toWire();
					DatagramPacket sendPacket = new DatagramPacket(responseData, responseData.length, clientAddress, clientPort);
					socket.send(sendPacket);
				} else {
					LOG.warn("Received unhandled DNS query\n{}", query);
				}
			}
			catch (Exception e) {
				LOG.error("Error occurred during DNS request processing", e);
			}
		}
		socket.close();
		LOG.info("DNSDock java is stopped");
	}

	private Message getQueryResponse(Message query, byte[] packetData) {
		int flags = 0;
		Record queryRecord = query.getQuestion();
		TSIGRecord queryTSIG = query.getTSIG();
		TSIG tsig = null;
		if (queryTSIG != null) {
			tsig = TSIGs.get(queryTSIG.getName());
			if (tsig == null || tsig.verify(query, packetData, null) != Rcode.NOERROR) {
				return formerrMessage(packetData);
			}
		}

		OPTRecord queryOPT = query.getOPT();
		if (queryOPT != null && (queryOPT.getFlags() & ExtendedFlags.DO) != 0) {
			flags = FLAG_DNSSECOK;
		}

		Message response = new Message(query.getHeader().getID());
		response.getHeader().setFlag(Flags.QR);
		if (query.getHeader().getFlag(Flags.RD)) {
			response.getHeader().setFlag(Flags.RD);
		}
		response.addRecord(queryRecord, Section.QUESTION);

		Name name = queryRecord.getName();
		int type = queryRecord.getType();
		if (!Type.isRR(type) && type != Type.ANY) {
			return errorMessage(query, Rcode.NOTIMP);
		}
		byte rcode = addAnswer(response, name, type, 0, flags);
		if (rcode != Rcode.NOERROR && rcode != Rcode.NXDOMAIN) {
			return errorMessage(query, rcode);
		}
		addAdditional(response, flags);
		if (queryOPT != null) {
			int optflags = (flags == FLAG_DNSSECOK) ? ExtendedFlags.DO : 0;
			OPTRecord opt = new OPTRecord((short) 4096, rcode, (byte) 0, optflags);
			response.addRecord(opt, Section.ADDITIONAL);
		}

		response.setTSIG(tsig, Rcode.NOERROR, queryTSIG);
		return response;
	}

	private Message formerrMessage(byte[] in) {
		Header header;
		try {
			header = new Header(in);
		}
		catch (IOException e) {
			return null;
		}
		return buildErrorMessage(header, Rcode.FORMERR, null);
	}

	private Message errorMessage(Message query, int rcode) {
		return buildErrorMessage(query.getHeader(), rcode, query.getQuestion());
	}

	private Message buildErrorMessage(Header header, int rcode, Record question) {
		Message response = new Message();
		response.setHeader(header);
		for (int i = 0; i < 4; i++) {
			response.removeAllRecords(i);
		}
		if (rcode == Rcode.SERVFAIL) {
			response.addRecord(question, Section.QUESTION);
		}
		header.setRcode(rcode);
		return response;
	}

	private byte addAnswer(Message response, Name name, int type, int iterations, int flags) {
		SetResponse sr;
		byte rcode = Rcode.NOERROR;

		if (iterations > 6) {
			return Rcode.NOERROR;
		}

		if (type == Type.SIG || type == Type.RRSIG) {
			type = Type.ANY;
			flags |= FLAG_SIGONLY;
		}

		Zone zone = findBestZone(name);
		if (zone != null) {
			sr = zone.findRecords(name, type);

			if (sr.isSuccessful()) {
				List<RRset> rrsets = sr.answers();
				for (RRset rrset : rrsets) {
					addRRset(name, response, rrset, Section.ANSWER, flags);
				}
				if (zone != null) {
					addNS(response, zone, flags);
					if (iterations == 0) {
						response.getHeader().setFlag(Flags.AA);
					}
				}
			}
		}
		return rcode;
	}

	private void addNS(Message response, Zone zone, int flags) {
		RRset nsRecords = zone.getNS();
		addRRset(nsRecords.getName(), response, nsRecords, Section.AUTHORITY, flags);
	}

	private void addAdditional(Message response, int flags) {
		addAdditional2(response, Section.ANSWER, flags);
		addAdditional2(response, Section.AUTHORITY, flags);
	}

	private void addAdditional2(Message response, int section, int flags) {
		for (Record r : response.getSection(section)) {
			Name glueName = r.getAdditionalName();
			if (glueName != null) {
				addGlue(response, glueName, flags);
			}
		}
	}

	private void addGlue(Message response, Name name, int flags) {
		RRset a = findExactMatch(name, Type.A);
		if (a == null) {
			return;
		}
		addRRset(name, response, a, Section.ADDITIONAL, flags);
	}

	private void addRRset(Name name, Message response, RRset rrset, int section, int flags) {
		for (int s = 1; s <= section; s++) {
			if (response.findRRset(name, rrset.getType(), s)) {
				return;
			}
		}
		if ((flags & FLAG_SIGONLY) == 0) {
			for (Record r : rrset.rrs()) {
				if (r.getName().isWild() && !name.isWild()) {
					r = r.withName(name);
				}
				response.addRecord(r, section);
			}
		}
		if ((flags & (FLAG_SIGONLY | FLAG_DNSSECOK)) != 0) {
			for (Record r : rrset.rrs()) {
				if (r.getName().isWild() && !name.isWild()) {
					r = r.withName(name);
				}
				response.addRecord(r, section);
			}
		}
	}

	private RRset findExactMatch(Name name, int type) {
		Zone zone = findBestZone(name);
		if (zone != null) {
			return zone.findExactMatch(name, type);
		}
		return null;
	}

	private Zone findBestZone(Name name) {
		Zone foundzone = getZone(name);
		if (foundzone != null) {
			return foundzone;
		}
		int labels = name.labels();
		for (int i = 1; i < labels; i++) {
			Name tname = new Name(name, i);
			foundzone = getZone(tname);
			if (foundzone != null) {
				return foundzone;
			}
		}
		return null;
	}

	private Zone getZone(Name name) {
		String hostname = name.toString(true);
		if (hostname.endsWith(DOCKER_DOMAIN)) {
			List<InetAddress> resolved = dockerHostsRegistry.resolve(hostname.replace(DOCKER_DOMAIN, ""));
			if (!resolved.isEmpty()) {
				LOG.debug("Hostname {} resolved to {}", hostname, resolved);
				Collection<Record> records = resolved.stream().map(ip -> new ARecord(name, DClass.IN, 0, ip)).collect(Collectors.toList());
				records = new ArrayList<>(records);
				records.add(new NSRecord(name, DClass.IN, 0, name));
				records.add(new SOARecord(
						name,
						DClass.IN,
						0,
						DNSDOCK_JAVA,
						DNSDOCK_JAVA_EMAIL,
						1000,
						1000,
						3600,
						1000,
						0));
				try {
					return new Zone(name, records.toArray(new Record[0]));
				}
				catch (IOException e) {
					throw new IllegalStateException(e);
				}
			}
		}
		return null;
	}
}