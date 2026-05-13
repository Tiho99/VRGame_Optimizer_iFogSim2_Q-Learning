package org.fog.test.perfeval;

import java.util.ArrayList;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;



import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.Actuator;
import org.fog.entities.FogBroker;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.placement.Controller;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementLatencyAware;
import org.fog.placement.ModulePlacementMapping;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;


/**
 * Simulation setup for case study 1 - EEG Beam Tractor Game
 * MODIFIED: Latency-Aware Placement with Q-Learning
 * Mini-projet TCD 2025/2026 - 4 Ingenieur IA
 * Universite Abou Bekr Belkaid - Tlemcen
 */
public class VRGameFog {

    static List<FogDevice> fogDevices = new ArrayList<FogDevice>();
    static List<Sensor>    sensors    = new ArrayList<Sensor>();
    static List<Actuator>  actuators  = new ArrayList<Actuator>();

    static boolean CLOUD = false;

    static int    numOfDepts            = 2;
    static int    numOfMobilesPerDept   = 5;
    static double EEG_TRANSMISSION_TIME = 5;

    // Baseline measured with default EdgeWards placement
    static double BASELINE_LATENCY = 229.33;


    
    
    
     	public static void main(String[] args) {
        System.out.println("Starting VRGame... !");

        // Candidate devices for concentration_calculator
        String[] candidateDevices = {"d-0", "d-1", "proxy-server", "cloud"};
        int NUM_EPISODES = 4; // one per device

        ModulePlacementLatencyAware mp = null;

        try {
            for (int episode = 0; episode < NUM_EPISODES; episode++) {
                String targetDevice = candidateDevices[episode];
                System.out.println("\n===== EPISODE " + (episode+1)
                    + " — placing concentration_calculator on: "
                    + targetDevice + " =====");

                // Reset lists each episode
                fogDevices.clear();
                sensors.clear();
                actuators.clear();

                Log.disable();
                CloudSim.init(1, Calendar.getInstance(), false);
                CloudSim.terminateSimulation(1000);

                String appId = "vr_game";
                FogBroker broker = new FogBroker("broker");
                Application application = createApplication(appId, broker.getId());
                application.setUserId(broker.getId());

                createFogDevices(broker.getId(), appId);

                ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();
                moduleMapping.addModuleToDevice("connector", "cloud");

                // Force placement on chosen device this episode
                moduleMapping.addModuleToDevice(
                    "concentration_calculator", targetDevice);

                mp = new ModulePlacementLatencyAware(
                    fogDevices, application, moduleMapping);

                Controller controller = new Controller(
                    "master-controller", fogDevices, sensors, actuators);
                controller.submitApplication(application, 0, mp);

                TimeKeeper.getInstance().setSimulationStartTime(
                    Calendar.getInstance().getTimeInMillis());

                CloudSim.startSimulation();
                CloudSim.stopSimulation();

                // ── Measure latency & update Q-table ──────────
                Map<Integer, Double> avgMap = TimeKeeper.getInstance()
                                                        .getLoopIdToCurrentAverage();
                if (avgMap != null && !avgMap.isEmpty()) {
                    for (double latency : avgMap.values()) {
                        if (latency > 0) {
                            List<Integer> devices = mp.getModuleToDeviceMap()
                                                      .get("concentration_calculator");
                            if (devices != null) {
                                for (int deviceId : devices) {
                                    mp.updateQTable(
                                        "concentration_calculator",
                                        deviceId, latency);
                                }
                            }
                        }
                    }
                }

                // Print Q-table after each episode
                mp.printQTable();
            }

            // ── Final results after all episodes ──────────────
            exportToJson(mp);
            printResults(mp);
            System.out.println("VRGame finished!");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // ═══════════════════════════════════════════════════════
    // PRINT RESULTS METHOD (inchangé)
    // ═══════════════════════════════════════════════════════
    private static void printResults(
            ModulePlacementLatencyAware mp) {

        System.out.println(
            "\n╔══════════════════════════════════════════════╗");
        System.out.println(
            "║        RÉSULTATS D'OPTIMISATION              ║");
        System.out.println(
            "╠══════════════════════════════════════════════╣");
        System.out.printf(
            "║  Baseline  (EdgeWards)    : %7.2f ms       ║%n",
            BASELINE_LATENCY);
        System.out.println(
            "╠══════════════════════════════════════════════╣");

        // ── Read latency safely from TimeKeeper ────────────
        double optimizedLatency = -1.0;

        Map<Integer, Double> avgMap =
            TimeKeeper.getInstance()
                      .getLoopIdToCurrentAverage();

        if (avgMap != null && !avgMap.isEmpty()) {
            for (Map.Entry<Integer, Double> entry
                    : avgMap.entrySet()) {

                if (entry.getValue() == null) continue;

                optimizedLatency = entry.getValue();

                double gain = ((BASELINE_LATENCY
                                - optimizedLatency)
                               / BASELINE_LATENCY) * 100.0;

                System.out.printf(
                    "║  Optimisé  (LatencyAware) : %7.2f ms       ║%n",
                    optimizedLatency);
                System.out.printf(
                    "║  Amélioration             : %7.2f %%        ║%n",
                    gain);
            }
        } else {
            System.out.println(
                "║  Latence : voir APPLICATION LOOP DELAYS     ║");
        }

        // ── Module Placement ───────────────────────────────
        System.out.println(
            "╠══════════════════════════════════════════════╣");
        System.out.println(
            "║           PLACEMENT DES MODULES              ║");
        System.out.println(
            "╠══════════════════════════════════════════════╣");

        for (FogDevice dev : fogDevices) {
            List<AppModule> mods =
                mp.getDeviceToModuleMap()
                  .get(dev.getId());

            if (mods != null && !mods.isEmpty()) {
                for (AppModule mod : mods) {
                    System.out.printf(
                        "║  %-22s → %-18s  ║%n",
                        mod.getName(),
                        dev.getName());
                }
            }
        }

        // ── Energy ─────────────────────────────────────────
        System.out.println(
            "╠══════════════════════════════════════════════╣");
        System.out.println(
            "║           ÉNERGIE CONSOMMÉE                  ║");
        System.out.println(
            "╠══════════════════════════════════════════════╣");

        for (FogDevice dev : fogDevices) {
            System.out.printf(
                "║  %-20s : %12.2f J          ║%n",
                dev.getName(),
                dev.getEnergyConsumption());
        }

        System.out.println(
            "╠══════════════════════════════════════════════╣");

        if (optimizedLatency > 0) {
            if (optimizedLatency < BASELINE_LATENCY) {
                System.out.println(
                    "║  ✓ OPTIMISATION RÉUSSIE                      ║");
                System.out.printf(
                    "║  Latence réduite de %.1f ms → %.1f ms    ║%n",
                    BASELINE_LATENCY, optimizedLatency);
            } else {
                System.out.println(
                    "║  ✗ Pas d'amélioration détectée               ║");
            }
        }

        System.out.println(
            "╚══════════════════════════════════════════════╝");
    }

    // ═══════════════════════════════════════════════════════
    // CREATE FOG DEVICES (inchangé)
    // ═══════════════════════════════════════════════════════
    private static void createFogDevices(int userId,
                                         String appId) {
        FogDevice cloud = createFogDevice(
            "cloud", 44800, 40000, 100, 10000,
            0, 0.01, 16*103, 16*83.25);
        cloud.setParentId(-1);

        FogDevice proxy = createFogDevice(
            "proxy-server", 2800, 4000, 10000, 10000,
            1, 0.0, 107.339, 83.4333);
        proxy.setParentId(cloud.getId());
        proxy.setUplinkLatency(100);

        fogDevices.add(cloud);
        fogDevices.add(proxy);

        for (int i = 0; i < numOfDepts; i++) {
            addGw(i+"", userId, appId, proxy.getId());
        }
    }

    private static FogDevice addGw(String id,
                                   int userId,
                                   String appId,
                                   int parentId) {
        FogDevice dept = createFogDevice(
            "d-"+id, 2800, 4000, 10000, 10000,
            1, 0.0, 107.339, 83.4333);
        fogDevices.add(dept);
        dept.setParentId(parentId);
        dept.setUplinkLatency(4);

        for (int i = 0; i < numOfMobilesPerDept; i++) {
            String mobileId = id+"-"+i;
            FogDevice mobile = addMobile(
                mobileId, userId, appId, dept.getId());
            mobile.setUplinkLatency(2);
            fogDevices.add(mobile);
        }
        return dept;
    }

    private static FogDevice addMobile(String id,
                                       int userId,
                                       String appId,
                                       int parentId) {
        FogDevice mobile = createFogDevice(
            "m-"+id, 1000, 1000, 10000, 270,
            3, 0, 87.53, 82.44);
        mobile.setParentId(parentId);

        Sensor eegSensor = new Sensor(
            "s-"+id, "EEG", userId, appId,
            new DeterministicDistribution(
                EEG_TRANSMISSION_TIME));
        sensors.add(eegSensor);

        Actuator display = new Actuator(
            "a-"+id, userId, appId, "DISPLAY");
        actuators.add(display);

        eegSensor.setGatewayDeviceId(mobile.getId());
        eegSensor.setLatency(6.0);
        display.setGatewayDeviceId(mobile.getId());
        display.setLatency(1.0);

        return mobile;
    }

    private static FogDevice createFogDevice(
            String nodeName, long mips, int ram,
            long upBw, long downBw, int level,
            double ratePerMips,
            double busyPower, double idlePower) {

        List<Pe> peList = new ArrayList<Pe>();
        peList.add(new Pe(0,
            new PeProvisionerOverbooking(mips)));

        int  hostId  = FogUtils.generateEntityId();
        long storage = 1000000;
        int  bw      = 10000;

        PowerHost host = new PowerHost(
            hostId,
            new RamProvisionerSimple(ram),
            new BwProvisionerOverbooking(bw),
            storage, peList,
            new StreamOperatorScheduler(peList),
            new FogLinearPowerModel(busyPower, idlePower));

        List<Host> hostList = new ArrayList<Host>();
        hostList.add(host);

        String arch           = "x86";
        String os             = "Linux";
        String vmm            = "Xen";
        double time_zone      = 10.0;
        double cost           = 3.0;
        double costPerMem     = 0.05;
        double costPerStorage = 0.001;
        double costPerBw      = 0.0;

        LinkedList<Storage> storageList =
            new LinkedList<Storage>();

        FogDeviceCharacteristics characteristics =
            new FogDeviceCharacteristics(
                arch, os, vmm, host, time_zone,
                cost, costPerMem,
                costPerStorage, costPerBw);

        FogDevice fogdevice = null;
        try {
            fogdevice = new FogDevice(
                nodeName, characteristics,
                new AppModuleAllocationPolicy(hostList),
                storageList, 10, upBw, downBw,
                0, ratePerMips);
        } catch (Exception e) {
            e.printStackTrace();
        }

        fogdevice.setLevel(level);
        return fogdevice;
    }

    // ═══════════════════════════════════════════════════════
    // CREATE APPLICATION (inchangé)
    // ═══════════════════════════════════════════════════════
    private static Application createApplication(
            String appId, int userId) {

        Application application =
            Application.createApplication(appId, userId);

        application.addAppModule("client", 10);
        application.addAppModule(
            "concentration_calculator", 10);
        application.addAppModule("connector", 10);

        if (EEG_TRANSMISSION_TIME == 10)
            application.addAppEdge(
                "EEG", "client", 2000, 500,
                "EEG", Tuple.UP, AppEdge.SENSOR);
        else
            application.addAppEdge(
                "EEG", "client", 3000, 500,
                "EEG", Tuple.UP, AppEdge.SENSOR);

        application.addAppEdge(
            "client", "concentration_calculator",
            3500, 500, "_SENSOR",
            Tuple.UP, AppEdge.MODULE);
        application.addAppEdge(
            "concentration_calculator", "connector",
            100, 1000, 1000, "PLAYER_GAME_STATE",
            Tuple.UP, AppEdge.MODULE);
        application.addAppEdge(
            "concentration_calculator", "client",
            14, 500, "CONCENTRATION",
            Tuple.DOWN, AppEdge.MODULE);
        application.addAppEdge(
            "connector", "client",
            100, 28, 1000, "GLOBAL_GAME_STATE",
            Tuple.DOWN, AppEdge.MODULE);
        application.addAppEdge(
            "client", "DISPLAY",
            1000, 500, "SELF_STATE_UPDATE",
            Tuple.DOWN, AppEdge.ACTUATOR);
        application.addAppEdge(
            "client", "DISPLAY",
            1000, 500, "GLOBAL_STATE_UPDATE",
            Tuple.DOWN, AppEdge.ACTUATOR);

        application.addTupleMapping(
            "client", "EEG", "_SENSOR",
            new FractionalSelectivity(0.9));
        application.addTupleMapping(
            "client", "CONCENTRATION",
            "SELF_STATE_UPDATE",
            new FractionalSelectivity(1.0));
        application.addTupleMapping(
            "concentration_calculator",
            "_SENSOR", "CONCENTRATION",
            new FractionalSelectivity(1.0));
        application.addTupleMapping(
            "client", "GLOBAL_GAME_STATE",
            "GLOBAL_STATE_UPDATE",
            new FractionalSelectivity(1.0));

        final AppLoop loop1 = new AppLoop(
            new ArrayList<String>() {{
                add("EEG");
                add("client");
                add("concentration_calculator");
                add("client");
                add("DISPLAY");
            }});

        List<AppLoop> loops =
            new ArrayList<AppLoop>() {{ add(loop1); }};
        application.setLoops(loops);

        return application;
    }
    
    private static void exportToJson(ModulePlacementLatencyAware mp) {
        try (FileWriter fw = new FileWriter("simulation_data.json")) {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            
            // Devices
            json.append("  \"devices\": [\n");
            for (int i = 0; i < fogDevices.size(); i++) {
                FogDevice d = fogDevices.get(i);
                json.append("    {")
                    .append("\"id\":").append(d.getId()).append(",")
                    .append("\"name\":\"").append(escapeJson(d.getName())).append("\",")
                    .append("\"level\":").append(d.getLevel()).append(",")
                    .append("\"parentId\":").append(d.getParentId()).append(",")
                    .append("\"energy\":").append(d.getEnergyConsumption())
                    .append("}");
                if (i < fogDevices.size() - 1) json.append(",");
                json.append("\n");
            }
            json.append("  ],\n");
            
            // Placements
            json.append("  \"placements\": [\n");
            List<String> placementEntries = new ArrayList<>();
            for (FogDevice dev : fogDevices) {
                List<AppModule> mods = mp.getDeviceToModuleMap().get(dev.getId());
                if (mods != null) {
                    for (AppModule mod : mods) {
                        placementEntries.add(String.format(
                            "    {\"module\":\"%s\",\"device\":\"%s\"}",
                            escapeJson(mod.getName()), escapeJson(dev.getName())
                        ));
                    }
                }
            }
            json.append(String.join(",\n", placementEntries));
            json.append("\n  ],\n");
            
            // Résultats
            Map<Integer, Double> avgMap = TimeKeeper.getInstance().getLoopIdToCurrentAverage();
            double latency = -1.0;
            if (avgMap != null && !avgMap.isEmpty()) {
                for (double val : avgMap.values()) {
                    if (val > 0) latency = val;
                }
            }
            json.append("  \"baseline_latency\": ").append(BASELINE_LATENCY).append(",\n");
            json.append("  \"optimized_latency\": ").append(latency).append("\n");
            if (latency > 0) {
                double gain = (BASELINE_LATENCY - latency) / BASELINE_LATENCY * 100;
                json.append("  ,\"gain_percent\": ").append(gain).append("\n");
            }
            json.append("}");
            
            fw.write(json.toString());
            System.out.println("Données exportées dans simulation_data.json");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}