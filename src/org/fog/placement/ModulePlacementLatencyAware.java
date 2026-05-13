package org.fog.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.entities.FogDevice;

/**
 * Latency-Aware Placement with Q-Learning
 * Mini-projet TCD 2025/2026 — 4 Ingenieur IA
 * Universite Abou Bekr Belkaid — Tlemcen
 *
 * Placement rules:
 *   connector              → cloud   (level 0) always
 *   concentration_calculator → fog gateway d-x (level 1)
 *   client                 → mobile  (level 3)
 */
public class ModulePlacementLatencyAware extends ModulePlacement {

    // ═══════════════════════════════════════════════
    // Q-LEARNING TABLE
    // ═══════════════════════════════════════════════
    private Map<String, Map<Integer, Double>> qTable
        = new HashMap<String, Map<Integer, Double>>();

    private static final double ALPHA   = 0.5;
    private static final double GAMMA   = 0.9;
    private static final double EPSILON = 0.1;

    // ═══════════════════════════════════════════════
    // CONSTRUCTOR
    // ═══════════════════════════════════════════════
    public ModulePlacementLatencyAware(
            List<FogDevice> fogDevices,
            Application application,
            ModuleMapping moduleMapping) {

        setFogDevices(fogDevices);
        setApplication(application);
        setModuleToDeviceMap(
            new HashMap<String, List<Integer>>());
        setDeviceToModuleMap(
            new HashMap<Integer, List<AppModule>>());

        mapModules();
    }

    // ═══════════════════════════════════════════════
    // CORE: PLACE ALL MODULES
    // ═══════════════════════════════════════════════
    @Override
    public void mapModules() {
        System.out.println(
            "\n[LatencyAware] ===== PLACEMENT START =====");

        for (AppModule module : getApplication().getModules()) {
            String    name   = module.getName();
            FogDevice chosen = null;

            // ── Explicit placement rules ──────────────
            if (name.equals("connector")) {
                // connector always on cloud
                chosen = getDeviceByName("cloud");

            } else if (name.equals(
                           "concentration_calculator")) {
                // calculator on best fog node (level 1)
                // NOT mobile, NOT cloud
                chosen = getBestFogNode(module);

            } else if (name.equals("client")) {
                // client on any mobile (level 3)
                chosen = getFirstAvailableMobile(module);
            }

            // ── Fallback ──────────────────────────────
            if (chosen == null) {
                chosen = heuristicSelect(module);
            }
            if (chosen == null) {
                chosen = getCloudDevice();
                System.out.println(
                    "[LatencyAware] FALLBACK→cloud: "
                    + name);
            }

            // ── Register placement ────────────────────
            registerPlacement(module, chosen);

            System.out.printf(
                "[LatencyAware]  %-30s → %-15s"
                + "(level: %d)%n",
                name,
                chosen.getName(),
                chosen.getLevel());
        }

        System.out.println(
            "[LatencyAware] ===== PLACEMENT END =======\n");
    }

    // ═══════════════════════════════════════════════
    // REGISTER: write into both maps
    // ═══════════════════════════════════════════════
    private void registerPlacement(AppModule module,
                                   FogDevice device) {
        // moduleToDeviceMap: String → List<Integer>
        if (!getModuleToDeviceMap()
                .containsKey(module.getName())) {
            getModuleToDeviceMap().put(
                module.getName(),
                new ArrayList<Integer>());
        }
        getModuleToDeviceMap()
            .get(module.getName())
            .add(device.getId());

        // deviceToModuleMap: Integer → List<AppModule>
        if (!getDeviceToModuleMap()
                .containsKey(device.getId())) {
            getDeviceToModuleMap().put(
                device.getId(),
                new ArrayList<AppModule>());
        }
        getDeviceToModuleMap()
            .get(device.getId())
            .add(module);
    }

    // ═══════════════════════════════════════════════
    // GET BEST FOG NODE (level=1, name starts with d-)
    // This is where concentration_calculator goes
    // ═══════════════════════════════════════════════
    private FogDevice getBestFogNode(AppModule module) {
        FogDevice best = null;

        for (FogDevice device : getFogDevices()) {
            // level 1 = gateway (d-0, d-1)
            // NOT proxy-server, NOT cloud, NOT mobile
            if (device.getLevel() != 1) continue;
            if (device.getName().contains("proxy")) continue;
            if (!canHost(device, module)) continue;

            // Take first available gateway
            if (best == null) {
                best = device;
            }
        }

        // Fallback: try proxy-server (also level 1)
        if (best == null) {
            best = getDeviceByName("proxy-server");
            if (best != null && !canHost(best, module)) {
                best = null;
            }
        }

        return best;
    }

    // ═══════════════════════════════════════════════
    // GET FIRST AVAILABLE MOBILE (level=3)
    // This is where client module goes
    // ═══════════════════════════════════════════════
    private FogDevice getFirstAvailableMobile(
            AppModule module) {
        for (FogDevice device : getFogDevices()) {
            if (device.getLevel() == 3
                    && canHost(device, module)) {
                return device;
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════
    // HEURISTIC FALLBACK: Q-table or level-based
    // ═══════════════════════════════════════════════
    private FogDevice selectDevice(AppModule module) {
        String  state  = module.getName();
        boolean noData = !qTable.containsKey(state)
                      || qTable.get(state).isEmpty();

        if (noData || Math.random() < EPSILON) {
            return heuristicSelect(module);
        } else {
            return qTableSelect(state, module);
        }
    }

    private FogDevice heuristicSelect(AppModule module) {
        // Find device at level 1 (fog) that can host
        FogDevice fogChoice = getBestFogNode(module);
        if (fogChoice != null) return fogChoice;

        // Otherwise cloud
        return getCloudDevice();
    }

    // ═══════════════════════════════════════════════
    // Q-TABLE SELECT
    // ═══════════════════════════════════════════════
    private FogDevice qTableSelect(String moduleName,
                                   AppModule module) {
        Map<Integer, Double> qValues =
            qTable.get(moduleName);
        if (qValues == null)
            return heuristicSelect(module);

        FogDevice best  = null;
        double    bestQ = Double.NEGATIVE_INFINITY;

        for (FogDevice device : getFogDevices()) {
            if (!canHost(device, module)) continue;
            double q = qValues.getOrDefault(
                           device.getId(), 0.0);
            if (q > bestQ) {
                bestQ = q;
                best  = device;
            }
        }
        return (best != null)
               ? best
               : heuristicSelect(module);
    }

    // ═══════════════════════════════════════════════
    // Q-TABLE UPDATE
    // ═══════════════════════════════════════════════
    public void updateQTable(String moduleName,
                             int    deviceId,
                             double measuredLatency) {
        double reward = -measuredLatency;
        qTable.putIfAbsent(
            moduleName,
            new HashMap<Integer, Double>());
        double oldQ =
            qTable.get(moduleName)
                  .getOrDefault(deviceId, 0.0);
        double maxNextQ = getMaxQ(moduleName);
        double newQ = oldQ
                    + ALPHA * (reward
                               + GAMMA * maxNextQ
                               - oldQ);
        qTable.get(moduleName).put(deviceId, newQ);

        System.out.printf(
            "[Q-Learn] %-25s device#%-4d"
            + " %.2fms  Q:%.4f→%.4f%n",
            moduleName, deviceId,
            measuredLatency, oldQ, newQ);
    }

    // ═══════════════════════════════════════════════
    // RESOURCE CHECK
    // ═══════════════════════════════════════════════
    private boolean canHost(FogDevice device,
                            AppModule module) {
        if (device == null)           return false;
        if (device.getHost() == null) return false;

        double availMips =
            device.getHost().getTotalMips();
        long availRam =
            device.getHost().getRam();

        return (availMips >= module.getMips())
            && (availRam  >= module.getRam());
    }

    // ═══════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════
    private double getMaxQ(String moduleName) {
        if (!qTable.containsKey(moduleName)
             || qTable.get(moduleName).isEmpty()) {
            return 0.0;
        }
        return Collections.max(
                   qTable.get(moduleName).values());
    }

    protected FogDevice getDeviceByName(String name) {
        for (FogDevice d : getFogDevices()) { 
            if (d.getName().equals(name)) return d;
        }
        return null;
    }

    private FogDevice getCloudDevice() {
        FogDevice d = getDeviceByName("cloud");
        if (d != null) return d;
        for (FogDevice dev : getFogDevices()) {
            if (dev.getParentId() == -1) return dev;
        }
        return getFogDevices().get(0);
    }
    
    
    
    
    //////
    public void printQTable() {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println(  "║                     Q-TABLE                         ║");
        System.out.println(  "╚══════════════════════════════════════════════════════╝");

        if (qTable.isEmpty()) {
            System.out.println("  [Q-Table is empty — no updates have been made yet]");
            return;
        }

        for (Map.Entry<String, Map<Integer, Double>> moduleEntry : qTable.entrySet()) {
            String moduleName = moduleEntry.getKey();
            System.out.println("\n  Module: " + moduleName);
            System.out.println("  " + "─".repeat(45));
            System.out.printf("  %-12s | %-20s | %s%n", "Device ID", "Device Name", "Q-Value");
            System.out.println("  " + "─".repeat(45));

            for (Map.Entry<Integer, Double> entry : moduleEntry.getValue().entrySet()) {
                int    deviceId   = entry.getKey();
                double qValue     = entry.getValue();
                String deviceName = getDeviceNameById(deviceId);
                System.out.printf("  %-12d | %-20s | %.6f%n",
                    deviceId, deviceName, qValue);
            }
        }

        System.out.println("\n" + "═".repeat(56) + "\n");
    }

    // Helper to resolve device ID → name
    private String getDeviceNameById(int id) {
        for (FogDevice d : getFogDevices()) {
            if (d.getId() == id) return d.getName();
        }
        return "unknown";
    }
}