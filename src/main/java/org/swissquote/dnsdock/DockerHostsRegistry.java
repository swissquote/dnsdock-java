package org.swissquote.dnsdock;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventType;
import com.github.dockerjava.core.DockerClientBuilder;

public class DockerHostsRegistry {

	private final Map<String, ContainerAddresses> dockerContainers;
	private final DockerClient dockerClient;
	private final Logger log = LoggerFactory.getLogger(this.getClass());

	public DockerHostsRegistry() {
		dockerContainers = new ConcurrentHashMap<>();
		dockerClient = DockerClientBuilder.getInstance().build();
		for (Container c : dockerClient.listContainersCmd().exec()) {
			String containerName = c.getNames().length > 0 ? c.getNames()[0] : null;
			addContainerToCache(c.getId(), containerName, c.getImage());
		}
		dockerClient.eventsCmd().exec(new ResultCallback.Adapter<>() {
			@Override
			public void onNext(Event item) {
				log.debug("Received docker event {}", item);
				if (item.getType() != null && item.getType().equals(EventType.CONTAINER)) {
					if (item.getStatus().equals("stop")) {
						log.info("Processing docker container stop event {}", item);
						dockerContainers.remove(item.getId());
					}
					if (item.getStatus().equals("start")) {
						log.info("Processing docker container start event {}", item);
						String containerName = null;
						if (item.getActor().getAttributes() != null) {
							containerName = item.getActor().getAttributes().get("name");
						}
						addContainerToCache(item.getId(), containerName, item.getFrom());
					}
				}
			}
		});
	}

	public Map<String, JsonContainerData> getJsonData() {
		Map<String, JsonContainerData> json = new HashMap<>();
		dockerContainers.forEach((k, v) -> json.put(k, v.getJsonContainerData()));
		return json;
	}

	private String normalizeImageName(String imageName) {
		if (imageName.indexOf("/") != -1) {
			imageName = imageName.substring(imageName.indexOf("/") + 1);
		}
		if (imageName.indexOf(":") != -1) {
			imageName = imageName.substring(0, imageName.indexOf(":"));
		}
		return imageName;
	}

	private String normalizeContainerName(String containerName) {
		return containerName.startsWith("/") ? containerName.substring(1) : containerName;
	}

	private void addContainerToCache(String containerId, String containerName, String imageName) {
		InspectContainerResponse ic = dockerClient.inspectContainerCmd(containerId).exec();
		if (containerName != null) {
			containerName = normalizeContainerName(containerName);
		}
		ContainerAddresses containerAddresses = new ContainerAddresses(containerName, imageName);
		for (ContainerNetwork cn : ic.getNetworkSettings().getNetworks().values()) {
			try {
				InetAddress ip = InetAddress.getByName(cn.getIpAddress());
				Set<String> alias = new HashSet<>();
				if (cn.getAliases() != null) {
					cn.getAliases().forEach(a -> alias.add(a.replace(DnsDockJava.DOCKER_DOMAIN, "")));
				}
				if (containerName != null) {
					alias.add(containerName);
				}
				alias.add(normalizeImageName(imageName));
				log.info("Adding container {}:{} ip {} for {}", containerId, containerName, ip, alias);
				alias.forEach(a -> containerAddresses.add(ip, a));
			}
			catch (UnknownHostException e) {
				throw new IllegalStateException(e);
			}
		}
		dockerContainers.put(containerId, containerAddresses);
	}

	public List<InetAddress> resolve(String hostName) {
		List<InetAddress> matches = new ArrayList<>();
		for (ContainerAddresses containerAddresses : dockerContainers.values()) {
			InetAddress match = containerAddresses.matches(hostName);
			if (match != null) {
				matches.add(match);
			}
		}
		return matches;
	}

	private static class ContainerAddresses {

		private final Map<InetAddress, Set<String>> ipPerHostnamesAliases = new HashMap<>();

		private JsonContainerData jsonContainerData;

		public ContainerAddresses(String name, String imageName) {
			jsonContainerData = new JsonContainerData();
			jsonContainerData.image = imageName;
			jsonContainerData.name = name;
			jsonContainerData.ttl = -1;

		}

		public JsonContainerData getJsonContainerData() {
			return jsonContainerData;
		}

		void add(InetAddress ip, String mapping) {
			ipPerHostnamesAliases.computeIfAbsent(ip, i -> new HashSet<>()).add(mapping);
			jsonContainerData.ips = ipPerHostnamesAliases.keySet();
			Set<String> aliases = new HashSet<>();
			ipPerHostnamesAliases.values()
					.forEach(a -> a.forEach(a2 -> aliases.add(a2 + DnsDockJava.DOCKER_DOMAIN)));
			jsonContainerData.aliases = aliases;
		}

		InetAddress matches(String hostname) {
			return ipPerHostnamesAliases.entrySet().stream()
					.filter(e -> e.getValue().contains(hostname))
					.map(Map.Entry::getKey).findFirst()
					.orElse(null);
		}
	}

	public static class JsonContainerData {

		@JsonProperty("Name")
		String name;
		@JsonProperty("Image")
		String image;
		@JsonProperty("IPs")
		Set<InetAddress> ips;
		@JsonProperty("TTl")
		int ttl;
		@JsonProperty("Aliases")
		Set<String> aliases;

	}
}
