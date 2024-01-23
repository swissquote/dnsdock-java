package org.swissquote.dnsdock;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventType;
import com.github.dockerjava.core.DockerClientBuilder;

public class DockerHostsRegistry {

	private final Map<String, ContainerAddresses> dockerContainers = new ConcurrentHashMap<>();
	private final DockerClient dockerClient;

	private final Logger log = LoggerFactory.getLogger(this.getClass());

	public DockerHostsRegistry() {
		dockerClient = DockerClientBuilder.getInstance().build();
		for (Container c : dockerClient.listContainersCmd().exec()) {
			addContainerToCache(c.getId());
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
						addContainerToCache(item.getId());
					}
				}
			}
		});
	}

	private void addContainerToCache(String containerId) {
		InspectContainerResponse ic = dockerClient.inspectContainerCmd(containerId).exec();
		ContainerAddresses containerAddresses = new ContainerAddresses();
		for (ContainerNetwork cn : ic.getNetworkSettings().getNetworks().values()) {
			try {
				InetAddress ip = InetAddress.getByName(cn.getIpAddress());
				log.debug("Adding container {} ip {} for {}", containerId, ip, cn.getAliases());
				containerAddresses.ipPerHostnamesAliases.put(ip, cn.getAliases());
			}
			catch (UnknownHostException e) {
				throw new IllegalStateException(e);
			}
		}
		dockerContainers.put(containerId, containerAddresses);
	}

	public List<InetAddress> resolve(String hostName) {
		long start = System.currentTimeMillis();
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
		private final Map<InetAddress, List<String>> ipPerHostnamesAliases = new HashMap<>();

		public InetAddress matches(String hostname) {
			return ipPerHostnamesAliases.entrySet().stream()
					.filter(e -> e.getValue().contains(hostname))
					.map(Map.Entry::getKey).findFirst()
					.orElse(null);
		}

	}
}
