package org.fog.miniproject;

import java.util.*;

import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.entities.FogDevice;
import org.fog.placement.ModuleMapping;

/**
 * Placement glouton (greedy) pour minimiser la latence.
 * Algorithme léger : pas d'entraînement, pas de Q-table.
 * 
 * Principe :
 * 1. Trier les modules par complexité décroissante (les plus lourds d'abord)
 * 2. Pour chaque module, choisir le nœud qui minimise la latence estimée
 *    (réseau + traitement + congestion)
 * 3. Mettre à jour la charge CPU du nœud choisi
 * 4. Appliquer des contraintes métier (anomaly-detector pas en Fog L2,
 *    data-collector pas sur le cloud)
 */
public class GreedyPlacement {

    private final List<FogDevice> fogDevices;
    private final List<String> moduleNames;
    private final Map<String, Double> moduleComplexity = new HashMap<>();

    public GreedyPlacement(List<FogDevice> fogDevices, Application app) {
        this.fogDevices = fogDevices;
        this.moduleNames = new ArrayList<>();
        for (AppModule m : app.getModules()) {
            this.moduleNames.add(m.getName());
            this.moduleComplexity.put(m.getName(), complexity(m.getName()));
        }
        // Trier par complexité décroissante
        this.moduleNames.sort((a, b) ->
            Double.compare(moduleComplexity.get(b), moduleComplexity.get(a)));
    }

    public ModuleMapping computeMapping() {
        ModuleMapping mapping = ModuleMapping.createModuleMapping();
        Map<String, Double> cpuLoad = new HashMap<>();
        for (FogDevice d : fogDevices) {
            cpuLoad.put(d.getName(), 0.3); // charge initiale
        }

        for (String module : moduleNames) {
            String bestDevice = null;
            double bestLatency = Double.POSITIVE_INFINITY;

            for (FogDevice dev : fogDevices) {
                double latency = estimateLatency(module, dev, cpuLoad);
                if (latency < bestLatency) {
                    bestLatency = latency;
                    bestDevice = dev.getName();
                }
            }

            bestDevice = applyConstraints(module, bestDevice);
            mapping.addModuleToDevice(module, bestDevice);

            double addedLoad = moduleComplexity.get(module) / 100.0;
            double newLoad = cpuLoad.get(bestDevice) + addedLoad;
            cpuLoad.put(bestDevice, Math.min(1.0, newLoad));
        }

        return mapping;
    }

    private double estimateLatency(String module, FogDevice dev,
                                   Map<String, Double> cpuLoad) {
        double net;
        switch (dev.getLevel()) {
            case 0: net = 50.0; break;
            case 1: net = 10.0; break;
            case 2: net =  3.0; break;
            default: net = 20.0;
        }

        double load = cpuLoad.getOrDefault(dev.getName(), 0.3);
        double proc = moduleComplexity.get(module) * (1.0 + load * 2.0);
        double congest = load > 0.8 ? (load - 0.8) * 100.0 : 0.0;

        return net + proc + congest;
    }

    private double complexity(String module) {
        switch (module) {
            case "data-collector":   return 1.0;
            case "preprocessor":     return 3.0;
            case "anomaly-detector": return 15.0;
            case "alert-manager":    return 2.0;
            default:                 return 5.0;
        }
    }

    private String applyConstraints(String module, String suggestedDevice) {
        FogDevice dev = findDeviceByName(suggestedDevice);
        if (dev == null) return suggestedDevice;

        if (module.equals("anomaly-detector") && dev.getLevel() >= 2) {
            for (FogDevice d : fogDevices) {
                if (d.getLevel() == 1) return d.getName();
            }
        } else if (module.equals("data-collector") && dev.getLevel() == 0) {
            for (FogDevice d : fogDevices) {
                if (d.getLevel() == 2) return d.getName();
            }
        }
        return suggestedDevice;
    }

    private FogDevice findDeviceByName(String name) {
        for (FogDevice d : fogDevices) {
            if (d.getName().equals(name)) return d;
        }
        return null;
    }
}