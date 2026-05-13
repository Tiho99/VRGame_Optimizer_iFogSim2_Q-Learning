package org.fog.miniproject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
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
import org.fog.placement.ModulePlacementEdgewards;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;

/**
 * Mini-Projet : Optimisation de la Latence dans un Environnement Cloud-Fog IoT
 * Scénario    : Health Monitoring IoT
 * Métrique    : LATENCE uniquement (ms)
 * Algorithme  : GreedyPlacement (glouton) – plus léger que Q-Learning
 * Université Abou Bekr Belkaid – Tlemcen 2025/2026
 */
public class HealthIoTSimulation {

    static final double SENSOR_PERIOD = 100;
    static final int    NUM_ROOMS     = 2;

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║   MINI-PROJET : Optimisation Latence Cloud-Fog IoT           ║");
        System.out.println("║   Scénario    : Health Monitoring IoT                        ║");
        System.out.println("║   Métrique    : LATENCE (ms)                                 ║");
        System.out.println("║   Algorithme  : GreedyPlacement (glouton)                    ║");
        System.out.println("║   Université Abou Bekr Belkaid – Tlemcen 2025/2026           ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        // ── Simulation 1 : Baseline (sans optimisation) ──────────────────────
        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("  SIMULATION 1/2 : Sans Optimisation (Baseline)");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        double baselineLatency = runSimulation(null);
        System.out.printf("  >> Latence Baseline : %.3f ms%n", baselineLatency);

        // ── Simulation 2 : avec GreedyPlacement ───────────────────────────────
        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("  SIMULATION 2/2 : Avec GreedyPlacement");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        ModuleMapping greedyMapping = computeGreedyMapping();
        double greedyLatency = runSimulation(greedyMapping);
        System.out.printf("  >> Latence Greedy : %.3f ms%n", greedyLatency);

        // ── Comparaison finale ────────────────────────────────────────────────
        LatencyAnalyzer.compare(baselineLatency, greedyLatency);
    }

    /**
     * Calcule un placement glouton (GreedyPlacement) sans lancer iFogSim.
     */
    private static ModuleMapping computeGreedyMapping() {
        List<FogDevice> devs = new ArrayList<>();
        try {
            devs.add(makeDev("cloud",      44800, 40960, 0));
            devs.add(makeDev("gateway-0",  10000, 8192,  1));
            devs.add(makeDev("room-gw-0",  4000,  4096,  2));
            devs.add(makeDev("room-gw-1",  4000,  4096,  2));
        } catch (Exception e) {
            e.printStackTrace();
        }

        Application app = createApplication("greedy_app", 0);
        GreedyPlacement greedy = new GreedyPlacement(devs, app);
        return greedy.computeMapping();
    }

    /**
     * Lance une simulation iFogSim complète.
     * @param mapping  null = Baseline (anomaly-detector forcé sur gateway)
     *                 non-null = placement Greedy
     * @return latence moyenne mesurée (ms)
     */
    private static double runSimulation(ModuleMapping mapping) {
        Log.disable();

        List<FogDevice> fogDevices = new ArrayList<>();
        List<Sensor>    sensors    = new ArrayList<>();
        List<Actuator>  actuators  = new ArrayList<>();

        try {
            CloudSim.init(1, Calendar.getInstance(), false);

            FogBroker broker = new FogBroker("broker");
            Application app  = createApplication("health_iot", broker.getId());
            app.setUserId(broker.getId());

            // Topologie
            FogDevice cloud = makeDev("cloud", 44800, 40960, 0);
            cloud.setParentId(-1);
            fogDevices.add(cloud);

            FogDevice gw = makeDev("gateway-0", 10000, 8192, 1);
            gw.setParentId(cloud.getId());
            gw.setUplinkLatency(4);
            fogDevices.add(gw);

            for (int r = 0; r < NUM_ROOMS; r++) {
                FogDevice rgw = makeDev("room-gw-" + r, 4000, 4096, 2);
                rgw.setParentId(gw.getId());
                rgw.setUplinkLatency(2);
                fogDevices.add(rgw);

                Sensor s = new Sensor("heart-sensor-" + r, "HEART_RATE",
                        broker.getId(), "health_iot",
                        new DeterministicDistribution(SENSOR_PERIOD));
                s.setGatewayDeviceId(rgw.getId());
                s.setLatency(1.0);
                sensors.add(s);

                Actuator a = new Actuator("alert-act-" + r,
                        broker.getId(), "health_iot", "MEDICAL_ALERT");
                a.setGatewayDeviceId(rgw.getId());
                a.setLatency(1.0);
                actuators.add(a);
            }

            // Préparer le mapping
            ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();
            if (mapping != null) {
                moduleMapping = mapping;
            } else {
                // Baseline : placer anomaly-detector sur gateway
                moduleMapping.addModuleToDevice("anomaly-detector", "gateway-0");
            }

            Controller controller = new Controller("controller",
                    fogDevices, sensors, actuators);
            controller.submitApplication(app,
                    new ModulePlacementEdgewards(
                            fogDevices, sensors, actuators, app, moduleMapping));

            TimeKeeper.getInstance().setSimulationStartTime(
                    Calendar.getInstance().getTimeInMillis());

            // Supprimer les logs de la simulation
            java.io.PrintStream original = System.out;
            System.setOut(new java.io.PrintStream(
                    new java.io.OutputStream() {
                        public void write(int b) {}
                    }));

            CloudSim.startSimulation();
            CloudSim.stopSimulation();

            System.setOut(original);

            return LatencyAnalyzer.readLatency();

        } catch (Exception e) {
            System.out.println("  [ERREUR] " + e.getMessage());
            e.printStackTrace();
            return -1;
        }
    }

    public static Application createApplication(String appId, int userId) {
        Application app = Application.createApplication(appId, userId);

        app.addAppModule("data-collector",    100);
        app.addAppModule("preprocessor",      400);
        app.addAppModule("anomaly-detector", 1500);
        app.addAppModule("alert-manager",     100);

        app.addAppEdge("HEART_RATE",       "data-collector",
                500,  300,  "HEART_RATE",    Tuple.UP,   AppEdge.SENSOR);
        app.addAppEdge("data-collector",   "preprocessor",
                1000, 1000, "RAW_DATA",      Tuple.UP,   AppEdge.MODULE);
        app.addAppEdge("preprocessor",     "anomaly-detector",
                2000,  500, "FILTERED_DATA", Tuple.UP,   AppEdge.MODULE);
        app.addAppEdge("anomaly-detector", "alert-manager",
                500,  1000, "ANOMALY",       Tuple.DOWN, AppEdge.MODULE);
        app.addAppEdge("alert-manager",    "MEDICAL_ALERT",
                100,   28,  "ALERT_MSG",     Tuple.DOWN, AppEdge.ACTUATOR);

        app.addTupleMapping("data-collector",   "HEART_RATE",
                "RAW_DATA",      new FractionalSelectivity(1.0));
        app.addTupleMapping("preprocessor",     "RAW_DATA",
                "FILTERED_DATA", new FractionalSelectivity(1.0));
        app.addTupleMapping("anomaly-detector", "FILTERED_DATA",
                "ANOMALY",       new FractionalSelectivity(1.0));
        app.addTupleMapping("alert-manager",    "ANOMALY",
                "ALERT_MSG",     new FractionalSelectivity(1.0));

        final AppLoop loop = new AppLoop(new ArrayList<String>() {{
            add("HEART_RATE");
            add("data-collector");
            add("preprocessor");
            add("anomaly-detector");
            add("alert-manager");
            add("MEDICAL_ALERT");
        }});
        List<AppLoop> loops = new ArrayList<>();
        loops.add(loop);
        app.setLoops(loops);

        return app;
    }

    private static FogDevice makeDev(String name, long mips, int ram, int level)
            throws Exception {
        List<Pe> pes = new ArrayList<>();
        pes.add(new Pe(0, new PeProvisionerSimple(mips)));

        PowerHost host = new PowerHost(
                FogUtils.generateEntityId(),
                new RamProvisionerSimple(ram),
                new BwProvisionerSimple(10000),
                1000000L, pes,
                new StreamOperatorScheduler(pes),
                new FogLinearPowerModel(107.339, 83.433));

        List<Host> hosts = new ArrayList<>();
        hosts.add(host);

        FogDeviceCharacteristics c = new FogDeviceCharacteristics(
                "x86", "Linux", "Xen", host, 10.0, 3.0, 0.05, 0.001, 0.0);

        FogDevice dev = new FogDevice(name, c,
                new AppModuleAllocationPolicy(hosts),
                new LinkedList<Storage>(), 10, 10000, 10000, 0, 0.0);
        dev.setLevel(level);
        return dev;
    }
}