package org.mdpnp.apps.testapp.export;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.util.*;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Callback;

import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.apps.testapp.IceApplicationProvider;
import org.mdpnp.apps.testapp.export.FileAdapterApplicationFactory.PersisterUIController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class DataCollectorApp implements DataCollector.DataSampleEventListener {

    private static final Logger log = LoggerFactory.getLogger(DataCollectorApp.class);

    @FXML protected TreeView<Object> tree;
    @FXML protected TableView<Row> table;
    @FXML protected SplitPane masterPanel;
    @FXML protected BorderPane persisterContainer;
    @FXML protected Button startControl;
    @FXML protected VBox btns;
    
    protected ObservableList<Row> tblModel = FXCollections.observableArrayList();

    protected static class Row {
        private final String uniqueDeviceIdentifier, instanceId, metricId, devTime;
        private final float value;
        
        public Row(final String uniqueDeviceIdentifier, final String instanceId, 
                   final String metricId, final String devTime, final float value) {
            this.uniqueDeviceIdentifier = uniqueDeviceIdentifier;
            this.instanceId = instanceId;
            this.metricId = metricId;
            this.devTime = devTime;
            this.value = value;
        }
        
        public float getValue() {
            return value;
        }
        
        public String getDevTime() {
            return devTime;
        }
        public String getInstanceId() {
            return instanceId;
        }
        public String getMetricId() {
            return metricId;
        }
        public String getUniqueDeviceIdentifier() {
            return uniqueDeviceIdentifier;
        }
    }
    
    private DataCollector   dataCollector;

    private DataFilter      dataFilter;
    private final DeviceTreeModel deviceTreeModel = new DeviceTreeModel();
    private DeviceListModel deviceListModel;

    private List<PersisterUIController> supportedPersisters = new ArrayList<>();
    protected PersisterUIController currentPersister;
    
    public DataCollectorApp() {
        
    }
    
    
    
    public DataCollectorApp set(DataCollector dc, DeviceListModel deviceListModel) throws IOException {
        this.deviceListModel = deviceListModel;
        table.setItems(tblModel);
        // hold on to the references so that we we can unhook the listeners at the end
        //
        dataCollector   = dc;

        // device list model maintains the list of what is out there.
        // add a listener to it so that we can dynamically build a tree representation
        // of that information.
        //
        deviceListModel.getContents().addListener(deviceTreeModel);
        dataCollector.addDataSampleListener(deviceTreeModel);

        // create a data filter - it will act as as proxy between the data collector and
        // actual data consumers. all internal components with register with it for data
        // events.
        dataFilter = new DataFilter(deviceTreeModel);
//        dataCollector.addDataSampleListener(dataFilter);

        // add self as a listener so that we can show some busy
        // data in the central panel.
        dataCollector.addDataSampleListener(dataFilter);
        
        dataFilter.addDataSampleListener(this);

        tree.setCellFactory(new Callback<TreeView<Object>,TreeCell<Object>>() {

            @Override
            public TreeCell<Object> call(TreeView<Object> param) {
                return new DeviceTreeCell();
            }
            
        });

        tree.setShowRoot(false);
        tree.setRoot(deviceTreeModel);

        List<URL> supportedPersisterURLs = new ArrayList<URL>();
        supportedPersisterURLs.add(CSVPersister.class.getResource("CSVPersister.fxml"));
        supportedPersisterURLs.add(JdbcPersister.class.getResource("JdbcPersister.fxml"));
        supportedPersisterURLs.add(VerilogVCDPersister.class.getResource("VerilogVCDPersister.fxml"));
        supportedPersisterURLs.add(MongoPersister.class.getResource("MongoPersister.fxml"));

        final ToggleGroup group = new ToggleGroup();
        StackPane cards = new StackPane();
        persisterContainer.setCenter(cards);
        

        for (URL u : supportedPersisterURLs) {
            FXMLLoader loader = new FXMLLoader(u);
            Node parent = loader.load();
            final PersisterUIController controller = loader.getController();
            controller.setup();
            parent.setVisible(false);
            cards.getChildren().add(parent);
            String name = controller.getName();
            RadioButton btn = new RadioButton(name);
            btn.setUserData(controller);
            btns.getChildren().add(btn);
            group.getToggles().add(btn);
            supportedPersisters.add(controller);
            btn.setOnAction(new EventHandler<ActionEvent>() {

                @Override
                public void handle(ActionEvent event) {
                    if(null != currentPersister) {
                        dataFilter.removeDataSampleListener(currentPersister);
                        try {
                            currentPersister.stop();
                        } catch (Exception e) {
                            log.error("Stopping persister", e);
                        }
                        currentPersister = null;
                        startControl.setText("Start");
                    }
                    currentPersister = controller;
                    for(Node n : cards.getChildren()) {
                        n.setVisible(false);
                    }
                    
                    parent.setVisible(true);
                }
            });
        }
        ((RadioButton)btns.getChildren().get(0)).fire();
        
        return this;
    }

    public static void exceptionDialog(Throwable t) {
        log.warn("Exception displayed to user", t);
        Alert alert = new Alert(AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText("An Exception has occurred");
        alert.setContentText(t.getMessage());

        // Create expandable Exception.
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        String exceptionText = sw.toString();

        Label label = new Label("The exception stacktrace was:");

        TextArea textArea = new TextArea(exceptionText);
        textArea.setEditable(false);
        textArea.setWrapText(true);

        textArea.setMaxWidth(Double.MAX_VALUE);
        textArea.setMaxHeight(Double.MAX_VALUE);
        GridPane.setVgrow(textArea, Priority.ALWAYS);
        GridPane.setHgrow(textArea, Priority.ALWAYS);

        GridPane expContent = new GridPane();
        expContent.setMaxWidth(Double.MAX_VALUE);
        expContent.add(label, 0, 0);
        expContent.add(textArea, 0, 1);

        // Set expandable Exception into the dialog pane.
        alert.getDialogPane().setExpandableContent(expContent);

        alert.showAndWait();
    }
    
    @FXML public void clickStart(ActionEvent evt) {
        if("Start".equals(startControl.getText()) && currentPersister != null) {
            boolean v;
            try {
                v = currentPersister.start();
                if (v) {
                    dataFilter.addDataSampleListener(currentPersister);
                    startControl.setText("Stop");
                }
            } catch (Exception e) {
                exceptionDialog(e);
            }

        } else if("Stop".equals(startControl.getText()) && currentPersister != null) {
            dataFilter.removeDataSampleListener(currentPersister);
            try {
                currentPersister.stop();
            } catch (Exception e) {
                exceptionDialog(e);
            }
            startControl.setText("Start");
        }

    }
    
    public void stop() throws Exception {
        dataCollector.removeDataSampleListener(dataFilter);
        deviceListModel.getContents().removeListener(deviceTreeModel);

//        startControl.putValue("mdpnp.appender", null);

        for (FileAdapterApplicationFactory.PersisterUIController p : supportedPersisters) {
            dataFilter.removeDataSampleListener(p);
            p.stop();
        }
    }

    @Override
    public void handleDataSampleEvent(DataCollector.DataSampleEvent evt) throws Exception {
        // Add to the screen for visual.
        Value value = (Value)evt.getSource();

//        Numeric n = value.getNumeric();
        long ms = value.getDevTime(); // DataCollector.toMilliseconds(n.device_time);
        String devTime = DataCollector.dateFormats.get().format(new Date(ms));
        final Row row = new Row(value.getUniqueDeviceIdentifier(), ""+value.getInstanceId(),
                          value.getMetricId(), devTime, (float) value.getValue());
        Platform.runLater(new Runnable() {
            public void run() {
                tblModel.add(0, row);
                if(tblModel.size()>250) {
                    tblModel.subList(250, tblModel.size()).clear();
                }
                
            }
        });
    }

    public static void main(String[] args) throws Exception {

        final AbstractApplicationContext context =
                new ClassPathXmlApplicationContext(new String[]{"DriverContext.xml"});
        context.registerShutdownHook();

        FileAdapterApplicationFactory factory = new FileAdapterApplicationFactory();
        final IceApplicationProvider.IceApp app = factory.create(context);

        app.activate(context);
//        Component component = app.getUI();

//        JFrame frame = new JFrame("UITest");
//        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
//        frame.addWindowListener(new WindowAdapter() {
//            @Override
//            public void windowClosing(WindowEvent e) {
//                try {
//                    app.stop();
//                    app.destroy();
//                    log.info("App " + app.getDescriptor().getName() + " stoped OK");
//                } catch (Exception ex) {
//                    log.error("Failed to stop the app", ex);
//                }
//                super.windowClosing(e);
//            }
//        });
//
//        frame.getContentPane().setLayout(new BorderLayout());
//        frame.getContentPane().add(component, BorderLayout.CENTER);
//        frame.setSize(640, 480);
//        frame.setLocationRelativeTo(null);
//        frame.setVisible(true);
    }

}
