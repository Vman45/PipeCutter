package com.kz.pipeCutter.BBB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

import org.zeromq.ZContext;
import org.zeromq.ZFrame;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.PollItem;
import org.zeromq.ZMQ.Poller;
import org.zeromq.ZMQ.Socket;
import org.zeromq.ZMsg;

import com.kz.pipeCutter.ui.IHasPinDef;
import com.kz.pipeCutter.ui.PinDef;
import com.kz.pipeCutter.ui.Settings;
import com.kz.pipeCutter.ui.tab.GcodeViewer;
import com.kz.pipeCutter.ui.tab.MachinekitSettings;

import pb.Message;
import pb.Message.Container;
import pb.Types.ContainerType;
import pb.Types.ValueType;

public class BBBHalRComp implements Runnable {
	private String halRCompUri;
	public Socket socket = null;
	public static BBBHalRComp instance;
	private ZContext ctx;
	private pb.Message.Container.Builder builder;
	private Thread readThread;
	HashMap<String, pb.Object.Component.Builder> components = new HashMap<>();
	HashMap<String, pb.Object.Pin.Builder> pins = new HashMap<>();
	HashMap<Integer, PinDef> pinsByHandle = new HashMap<>();
	HashMap<String, PinDef> pinsByName = new HashMap<>();

	HashMap<String, String> halPins = new HashMap<>();

	public boolean isBinded;
	public boolean isTryingToBind;
	private long lastPingMs;

	private boolean shouldRead;

	public BBBHalRComp() {
		prepareBindContainer();
		initSocket();
		// scheduler.scheduleAtFixedRate(new Thread(this), 1000, 1000,
		// TimeUnit.MILLISECONDS);
		instance = this;
	}

	public static BBBHalRComp getInstance() {
		if (instance == null)
			instance = new BBBHalRComp();
		return instance;
	}

	public BBBHalRComp(String uri) {
		this();
		this.halRCompUri = uri;
		prepareBindContainer();
		initSocket();
		readThread = new Thread(this);
		readThread.start();
	}

	private void prepareBindContainer() {
		this.builder = Container.newBuilder();
		builder.setType(ContainerType.MT_HALRCOMP_BIND);

		List<IHasPinDef> savableControls = Settings.getInstance().getAllPinControls();
		for (IHasPinDef savableControl : savableControls) {
			if (savableControl.getPin() != null) {
				String pinName = savableControl.getPin().getPinName();
				String compName = pinName.split("\\.")[0];
				System.out.println(String.format("%s", pinName));
				pb.Object.Component.Builder comp = null;
				if (components.containsKey(compName)) {
					comp = components.get(compName);
				} else {
					comp = pb.Object.Component.newBuilder().setName(compName);
					components.put(compName, comp);
				}
				pb.Object.Pin.Builder pin = null;
				if (pins.containsKey(pinName)) {
					pin = pins.get(pinName);
				} else {
					pin = pb.Object.Pin.newBuilder().setName(pinName);
					pin.setDir(savableControl.getPin().getPinDir());
					pin.setType(savableControl.getPin().getPinType());
					pin.setName(savableControl.getPin().getPinName());
					pins.put(pin.getName(), pin);
					pinsByName.put(pin.getName(), savableControl.getPin());
				}
				comp.addPin(pin);
			}
		}

		int i = 0;
		for (pb.Object.Component.Builder comp : components.values()) {
			builder.addComp(comp);
			i++;
		}

	}

	public void run() {
		// Tick once per second, pulling in arriving messages
		// for (int centitick = 0; centitick < 4; centitick++) {
		// System.out.println(Thread.currentThread().getName());
		shouldRead = true;
		while (shouldRead) {
			if (socket != null) {
				PollItem[] pollItems = new PollItem[] { new PollItem(socket, Poller.POLLIN) };
				int rc = ZMQ.poll(pollItems, 1, 100);
				// System.out.println("loop: " + i);
				for (int l = 0; l < rc; l++) {
					ZMsg msg = ZMsg.recvMsg(socket);
					try {
						ZFrame frame = null;
						while (pollItems[0].isReadable() && (frame = msg.poll()) != null) {
							Container contReturned;
							String data = new String(frame.getData());
							if (!components.keySet().contains(data) && pinsByHandle.size() > 0) {
								contReturned = Message.Container.parseFrom(frame.getData());
								if (contReturned.getType().equals(ContainerType.MT_HALRCOMP_FULL_UPDATE)) {
									for (int i = 0; i < contReturned.getCompCount(); i++) {
										for (int j = 0; j < contReturned.getComp(i).getPinCount(); j++) {
											String value = null;
											if (contReturned.getComp(i).getPin(j).getType() == ValueType.HAL_FLOAT) {
												value = Double.valueOf(contReturned.getComp(i).getPin(j).getHalfloat()).toString();
											} else if (contReturned.getComp(i).getPin(j).getType() == ValueType.HAL_S32) {
												value = Integer.valueOf(contReturned.getComp(i).getPin(j).getHals32()).toString();
											} else if (contReturned.getComp(i).getPin(j).getType() == ValueType.HAL_U32) {
												value = Integer.valueOf(contReturned.getComp(i).getPin(j).getHalu32()).toString();
											} else if (contReturned.getComp(i).getPin(j).getType() == ValueType.HAL_BIT) {
												value = Boolean.valueOf(contReturned.getComp(i).getPin(j).getHalbit()).toString();
											}
											halPins.put(contReturned.getComp(i).getPin(j).getName(), value);

											if (BBBHalRComp.instance.pinsByName.get(contReturned.getComp(i).getPin(j).getName()) == null)
												throw new Exception("Missing PIN: " + contReturned.getComp(i).getPin(j).getName());

											if (BBBHalRComp.instance.pinsByHandle.get(contReturned.getComp(i).getPin(j).getHandle()) == null
													&& BBBHalRComp.instance.pinsByName.get(contReturned.getComp(i).getPin(j).getName()) != null)
												BBBHalRComp.instance.pinsByHandle.put(contReturned.getComp(i).getPin(j).getHandle(),
														BBBHalRComp.instance.pinsByName.get(contReturned.getComp(i).getPin(j).getName()));

										}
									}

									SwingUtilities.invokeLater(updateUi);
									Settings.getInstance().updateHalValues();

								} else if (contReturned.getType().equals(ContainerType.MT_PING)) {
									this.lastPingMs = System.currentTimeMillis();
									MachinekitSettings.getInstance().pingHalRcomp();
								} else if (contReturned.getType().equals(ContainerType.MT_HALRCOMP_INCREMENTAL_UPDATE)) {
									for (int j = 0; j < contReturned.getPinCount(); j++) {
										String value = null;
										PinDef pinDef = pinsByHandle.get(contReturned.getPin(j).getHandle());
										if (pinDef != null) {
											if (pinDef.getPinType() == ValueType.HAL_FLOAT) {
												value = Double.valueOf(contReturned.getPin(j).getHalfloat()).toString();
											} else if (pinDef.getPinType() == ValueType.HAL_S32) {
												value = Integer.valueOf(contReturned.getPin(j).getHals32()).toString();
											} else if (pinDef.getPinType() == ValueType.HAL_U32) {
												value = Integer.valueOf(contReturned.getPin(j).getHalu32()).toString();
											} else if (pinDef.getPinType() == ValueType.HAL_BIT) {
												value = Boolean.valueOf(contReturned.getPin(j).getHalbit()).toString();
											}
											halPins.put(pinDef.getPinName(), value);
										}
									}

									SwingUtilities.invokeLater(updateUi);

								} else {
									System.out.println(contReturned.getType().toString());
								}
							}
						}
						msg.destroy();
					} catch (Exception ex) {
						ex.printStackTrace();
					} finally {

					}
				}
			}
			Thread.yield();
		}
	}

	// Thread.currentThread().interrupt();

	public void initSocket() {
		isBinded = false;
		isTryingToBind = false;
		shouldRead = false;
		if (ctx != null && socket != null) {
			ctx.destroySocket(socket);
			ctx.destroy();
		}
		if (readThread != null) {
			shouldRead = false;
			while (readThread.isAlive()) {
				try {
					TimeUnit.MILLISECONDS.sleep(500);
					readThread.interrupt();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}

		this.halRCompUri = Settings.getInstance().getSetting("machinekit_halRCompService_url");
		ctx = new ZContext();
		// Set random identity to make tracing easier
		socket = ctx.createSocket(ZMQ.XSUB);

		Random rand = new Random(23424234);
		String identity = String.format("%04X-%04X", rand.nextInt(), rand.nextInt());

		socket.setIdentity(identity.getBytes());
		socket.setReceiveTimeOut(500);
		socket.setSendTimeOut(1000);
		socket.setRcvHWM(5000);
		socket.connect(this.halRCompUri);

		readThread = new Thread(this);
		readThread.setName("BBBHalRComp");
		readThread.start();
	}

	public Socket getSocket() {
		return this.socket;
	}

	public void interrupt() {
		readThread.interrupt();
	}

	public void startBind() {
		Settings.getInstance().log("Start bind...");
		this.isTryingToBind = true;
		Container container = this.builder.build();
		byte[] buff = container.toByteArray();
		String hexOutput = javax.xml.bind.DatatypeConverter.printHexBinary(buff);
		System.out.println("Message:    " + hexOutput);
		BBBHalCommand.instance.socket.send(buff, 0);
	}

	public void subcribe() {
		ArrayList<String> compNamesSubscribed = new ArrayList<>();
		List<IHasPinDef> savableControls = Settings.getInstance().getAllPinControls();
		for (IHasPinDef savableControl : savableControls) {
			if (savableControl.getPin() != null) {
				String compName = savableControl.getPin().getPinName().split("\\.")[0];
				if (!compNamesSubscribed.contains(compName)) {
					String subscription = Character.toString((char) 1) + compName;
					socket.send(subscription.getBytes(), 0);
					compNamesSubscribed.add(compName);
				}
			}
		}
	}

	public boolean isAlive() {
		if (this.lastPingMs != 0)
			return (System.currentTimeMillis() - this.lastPingMs < 3000);
		else
			return false;
	}

	private void prepareUpdateContainer() {
		this.builder = Container.newBuilder();
		builder.setType(ContainerType.MT_HALRCOMP_SET);

		List<IHasPinDef> savableControls = Settings.getInstance().getAllPinControls();
		for (IHasPinDef savableControl : savableControls) {
			if (savableControl.getPin() != null) {
				String pinName = savableControl.getPin().getPinName();
				String compName = pinName.split("\\.")[0];

				pb.Object.Component.Builder comp = null;
				if (components.containsKey(compName)) {
					comp = components.get(compName);
				} else {
					comp = pb.Object.Component.newBuilder().setName(compName);
					components.put(compName, comp);
				}
				pb.Object.Pin.Builder pin = null;
				if (pins.containsKey(pinName)) {
					pin = pins.get(pinName);
				} else {
					pin = pb.Object.Pin.newBuilder().setName(pinName);
					pin.setDir(savableControl.getPin().getPinDir());
					pin.setType(savableControl.getPin().getPinType());
					pin.setName(savableControl.getPin().getPinName());
					pins.put(pin.getName(), pin);
					pinsByName.put(pin.getName(), savableControl.getPin());
				}
				comp.addPin(pin);
			}
		}
	}

	public int getPinHandle(String pinName) {
		Iterator<Integer> it = pinsByHandle.keySet().iterator();
		while (it.hasNext()) {
			Integer handle = it.next();
			if (pinsByHandle.get(handle).getPinName().equals(pinName)) {
				return handle.intValue();
			}
		}
		return -1;
	}

	public void stop() {
		this.shouldRead = false;
	}

	Runnable updateUi = new Runnable() {

		@Override
		public void run() {
			if (halPins.get("mymotion.program-line") != null) {
				// GcodeViewer.instance.setLineNumber(Integer.valueOf(halPins.get("mymotion.program-line")).intValue());
				Settings.getInstance().setSetting("mymotion.program-line", Integer.valueOf(halPins.get("mymotion.program-line")));
			}
			GcodeViewer.instance.setPlasmaOn(Boolean.valueOf(halPins.get("mymotion.spindle-on")).booleanValue());
			
			/*
			Settings.getInstance().setSetting("mymotion.vx", Float.valueOf(halPins.get("mymotion.vx")));
			Settings.getInstance().setSetting("mymotion.dvx", Float.valueOf(halPins.get("mymotion.dvx")));
			Settings.getInstance().setSetting("mymotion.vz", Float.valueOf(halPins.get("mymotion.vz")));
			Settings.getInstance().setSetting("mymotion.dvz", Float.valueOf(halPins.get("mymotion.dvz")));
			Settings.getInstance().setSetting("mymotion.current-radius", String.format("%.3f", Float.valueOf(halPins.get("mymotion.current-radius"))));
			Settings.getInstance().setSetting("mymotion.vy", Float.valueOf(halPins.get("mymotion.vy")));
			Settings.getInstance().setSetting("mymotion.v", Float.valueOf(halPins.get("mymotion.v")));
			*/
			
//			double laserHeight1 = Float.valueOf(halPins.get("mymotion.laserHeight1"));
//			Settings.getInstance().setSetting("mymotion.laserHeight1", String.format("%.3f", laserHeight1));

			double actualVolts = Float.valueOf(halPins.get("myini.actual-volts"));
			Settings.getInstance().setSetting("myini.actual-volts", actualVolts);
			double requestedVolts = Float.valueOf(Settings.getInstance().getSetting("myini.volts-requested"));
			Settings.getInstance().plasmaSettings.seriesVoltConstTime.add(System.currentTimeMillis(), requestedVolts);
			Settings.getInstance().plasmaSettings.seriesVoltTime.add(System.currentTimeMillis(), actualVolts);
			Settings.getInstance().plasmaSettings.updateChartRange();

			Settings.getInstance().setSetting("myini.vel-status", String.format("%s", Boolean.valueOf(halPins.get("myini.vel-status"))));

			Settings.getInstance().setSetting("myini.thc-z-pos", Float.valueOf(halPins.get("myini.thc-z-pos")));
			Settings.getInstance().setSetting("myini.offset-value", Float.valueOf(halPins.get("myini.offset-value")));

			Settings.getInstance().setSetting("myini.arc-ok", String.format("%s", Boolean.valueOf(halPins.get("myini.arc-ok"))));
			
			
			
			
		}

	};

}
