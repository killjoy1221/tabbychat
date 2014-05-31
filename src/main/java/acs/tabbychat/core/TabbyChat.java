package acs.tabbychat.core;

/****************************************************
 * This document is Copyright ©(2012) and is the intellectual property of the author.
 * It may be not be reproduced under any circumstances except for personal, private
 * use as long as it remains in its unaltered, unedited form. It may not be placed on
 * any web site or otherwise distributed publicly without advance written permission.
 * Use of this mod on any other website or as a part of any public display is strictly
 * prohibited, and a violation of copyright.
 */

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.StringUtils;

import org.apache.logging.log4j.Logger;

import acs.tabbychat.gui.ChatBox;
import acs.tabbychat.gui.TCSettingsAdvanced;
import acs.tabbychat.gui.TCSettingsFilters;
import acs.tabbychat.gui.TCSettingsGeneral;
import acs.tabbychat.gui.TCSettingsServer;
import acs.tabbychat.jazzy.TCSpellCheckManager;
import acs.tabbychat.settings.ChannelDelimEnum;
import acs.tabbychat.settings.ColorCodeEnum;
import acs.tabbychat.settings.FormatCodeEnum;
import acs.tabbychat.settings.TCChatFilter;
import acs.tabbychat.threads.BackgroundUpdateCheck;
import acs.tabbychat.util.ChatComponentUtils;
import acs.tabbychat.util.ComponentList;
import acs.tabbychat.util.TabbyChatUtils;

public class TabbyChat {
	public static TabbyChat getInstance() {
		return instance;
	}

	private static Logger log = TabbyChatUtils.log;
	private volatile List<TCChatLine> lastChat = new ArrayList<TCChatLine>();
	private static boolean firstRun = true;
	public static boolean liteLoaded = false;
	public static boolean modLoaded = false;
	public static boolean forgePresent = false;
	private static boolean updateChecked = false;
	private static String mcversion = (new ServerData("", "")).gameVersion;
	public static boolean defaultUnicode;
	public static String version = TabbyChatUtils.version;
	public static Minecraft mc;
	public static TCSettingsGeneral generalSettings;
	public static TCSettingsServer serverSettings;
	public static TCSettingsFilters filterSettings;
	public static TCSettingsAdvanced advancedSettings;
	public static TCSpellCheckManager spellChecker;
	public LinkedHashMap<String, ChatChannel> channelMap = new LinkedHashMap<String, ChatChannel>();

	private static File chanDataFile;
	protected Calendar cal = Calendar.getInstance();
	protected Semaphore serverDataLock = new Semaphore(0, true);
	private Pattern chatChannelPatternClean = Pattern
			.compile("^\\[([\\p{L}0-9_]{1,10})\\]");
	private Pattern chatChannelPatternDirty = Pattern
			.compile("^\\[([\\p{L}0-9_]{1,10})\\]");
	private Pattern chatPMfromMePattern = null;
	private Pattern chatPMtoMePattern = null;
	private final ReentrantReadWriteLock lastChatLock = new ReentrantReadWriteLock(
			true);
	private final Lock lastChatReadLock = lastChatLock.readLock();

	private final Lock lastChatWriteLock = lastChatLock.writeLock();
	private static GuiNewChatTC gnc;

	private static TabbyChat instance = null;

	/**
	 * 
	 * @param gncInstance
	 * @return
	 */
	public static TabbyChat getInstance(GuiNewChatTC gncInstance) {
		if (instance == null) {
			instance = new TabbyChat(gncInstance);
		}
		return instance;
	}

	/**
	 * 
	 * @return
	 */
	public static String getNewestVersion() {
		String updateURL;
		if (liteLoaded) {
			updateURL = "http://tabbychat.port0.org/tabbychat/current_version.php?type=LL&mc="
					+ mcversion;
		} else {
			updateURL = "http://tabbychat.port0.org/tabbychat/current_version.php?mc="
					+ mcversion;
		}
		try {
			HttpURLConnection conn = (HttpURLConnection) new URL(updateURL)
					.openConnection();
			BufferedReader buffer = new BufferedReader(new InputStreamReader(
					conn.getInputStream()));
			String newestVersion = buffer.readLine();
			buffer.close();
			return newestVersion;
		} catch (Throwable e) {
			printErr("Unable to check for TabbyChat update.");
		}
		return TabbyChat.version;
	}

	/**
	 * Prints errors to the console
	 * 
	 * @param err
	 */
	public static void printErr(String err) {
		System.err.println("[TabbyChat] " + err);
		log.warn("[TABBYCHAT] " + err);
	}

	/**
	 * 
	 * @param err
	 * @param e
	 */
	public static void printException(String err, Exception e) {
		System.err.println("[TabbyChat] " + err);
		log.warn("[TABBYCHAT] " + err, e);
	}

	/**
	 * 
	 * @param msg
	 */
	public static void printMessageToChat(String msg) {
		if (TabbyChat.instance == null)
			return;
		if (!TabbyChat.instance.channelMap.containsKey("TabbyChat")) {
			TabbyChat.instance.channelMap.put("TabbyChat", new ChatChannel(
					"TabbyChat"));
		}
		boolean firstLine = true;
		List<String> split = mc.fontRenderer.listFormattedStringToWidth(msg,
				ChatBox.getMinChatWidth());
		for (String splitMsg : split) {
			if (firstLine)
				TabbyChat.instance.addToChannel("TabbyChat", new TCChatLine(
						mc.ingameGUI.getUpdateCounter(), new ChatComponentText(
								splitMsg), 0, true), false);
			else
				TabbyChat.instance.addToChannel("TabbyChat", new TCChatLine(
						mc.ingameGUI.getUpdateCounter(), new ChatComponentText(
								" " + splitMsg), 0, true), false);
			firstLine = false;
		}
	}

	private TabbyChat(GuiNewChatTC gncInstance) {
		mc = Minecraft.getMinecraft();
		gnc = gncInstance;
		generalSettings = new TCSettingsGeneral(this);
		serverSettings = new TCSettingsServer(this);
		filterSettings = new TCSettingsFilters(this);
		advancedSettings = new TCSettingsAdvanced(this);
		spellChecker = TCSpellCheckManager.getInstance();
		generalSettings.loadSettingsFile();
		advancedSettings.loadSettingsFile();
		defaultUnicode = mc.fontRenderer.getUnicodeFlag();
	}

	/**
	 * 
	 * @param ind
	 */
	public void activateIndex(int ind) {
		List<?> actives = this.getActive();
		if (actives.size() == 1) {
			int i = 1;
			Iterator<ChatChannel> iter = this.channelMap.values().iterator();
			ChatChannel chan;
			while (iter.hasNext()) {
				chan = iter.next();
				if (i == ind) {
					if (mc.currentScreen instanceof GuiChatTC)
						((GuiChatTC) mc.currentScreen)
								.checkCommandPrefixChange(
										this.channelMap.get(actives.get(0)),
										chan);
					this.resetDisplayedChat();
					return;
				}
				i++;
			}
		}
	}

	/**
	 * 
	 */
	public void activateNext() {
		List<?> actives = this.getActive();
		if (actives.size() == 1) {
			Iterator<ChatChannel> iter = this.channelMap.values().iterator();
			ChatChannel chan = iter.next();
			while (iter.hasNext()) {
				if (chan.getTitle().equals(actives.get(0))) {
					if (mc.currentScreen instanceof GuiChatTC)
						((GuiChatTC) mc.currentScreen)
								.checkCommandPrefixChange(chan, iter.next());
					this.resetDisplayedChat();
					return;
				}
				chan = iter.next();
			}
			if (chan.getTitle().equals(actives.get(0))) {
				iter = this.channelMap.values().iterator();
				if (iter.hasNext() && mc.currentScreen instanceof GuiChatTC)
					((GuiChatTC) mc.currentScreen).checkCommandPrefixChange(
							chan, iter.next());
				this.resetDisplayedChat();
			}
		}
	}

	/**
	 * 
	 */
	public void activatePrev() {
		List<?> actives = this.getActive();
		if (actives.size() == 1) {
			ListIterator<ChatChannel> iter = new ArrayList<ChatChannel>(
					this.channelMap.values()).listIterator(this.channelMap
					.size());
			ChatChannel chan = iter.previous();
			while (iter.hasPrevious()) {
				if (chan.getTitle().equals(actives.get(0))) {
					if (mc.currentScreen instanceof GuiChatTC)
						((GuiChatTC) mc.currentScreen)
								.checkCommandPrefixChange(chan, iter.previous());
					this.resetDisplayedChat();
					return;
				}
				chan = iter.previous();
			}
			if (chan.getTitle().equals(actives.get(0))) {
				chan.active = false;
				iter = new ArrayList<ChatChannel>(this.channelMap.values())
						.listIterator(this.channelMap.size());
				if (iter.hasPrevious() && mc.currentScreen instanceof GuiChatTC)
					((GuiChatTC) mc.currentScreen).checkCommandPrefixChange(
							chan, iter.previous());
				this.resetDisplayedChat();
			}
		}
	}

	private void addOptionalTimeStamp(List<TCChatLine> orig) {
		for (TCChatLine line : orig) {
			line.timeStamp = this.getTimeStamp();
		}
	}

	/**
	 * 
	 * @param _name
	 * @param thisChat
	 * @param visible
	 */
	public void addToChannel(String _name, List<TCChatLine> thisChat,
			boolean visible) {
		ChatChannel theChan = this.channelMap.get(_name);
		if (theChan != null && generalSettings.groupSpam.getValue()) {
			this.spamCheck(theChan, thisChat);
			if (!theChan.hasSpam) {
				for (TCChatLine cl : thisChat) {
					this.addToChannel(_name, cl, visible);
				}
			}
		} else {
			for (TCChatLine cl : thisChat) {
				this.addToChannel(_name, cl, visible);
			}
		}
	}

	/**
	 * 
	 * @param name
	 * @param thisChat
	 * @param visible
	 */
	public void addToChannel(String name, TCChatLine thisChat, boolean visible) {
		if (serverSettings.ignoredChanPattern.matcher(name).matches())
			return;

		ChatChannel theChan = this.channelMap.get(name);
		if (theChan == null) {
			if (this.channelMap.size() >= 20)
				return;
			theChan = new ChatChannel(name);
			this.channelMap.put(name, theChan);
			if (mc.currentScreen instanceof GuiChatTC) {
				((GuiChatTC) mc.currentScreen).addChannelLive(theChan);
			}
		}

		theChan.addChat(thisChat, visible);
		theChan.trimLog();
	}

	/**
	 * 
	 * @param name
	 * @return
	 */
	public boolean channelExists(String name) {
		return (this.channelMap.get(name) != null);
	}

	/**
	 * 
	 */
	public void checkServer() {
		if (!updateChecked) {
			updateChecked = true;
			BackgroundUpdateCheck buc = new BackgroundUpdateCheck();
			buc.start();
		}

		if (!serverSettings.serverIP.equals(TabbyChatUtils.getServerIp())) {
			this.storeChannelData();
			this.channelMap.clear();
			if (this.enabled()) {
				this.enable();
				this.resetDisplayedChat();
			} else
				this.disable();
		}
		return;
	}

	/**
	 * 
	 * @param name
	 */
	public void createNewChannel(String name) {
		if (this.channelExists(name))
			return;
		if (name == null || name.length() <= 0 || this.channelMap.size() >= 20)
			return;
		this.channelMap.put(name, new ChatChannel(name));
		return;
	}

	/**
	 * Disables modded chat.
	 */
	public void disable() {
		this.channelMap.clear();
		this.channelMap.put("*", new ChatChannel("*"));
	}

	/**
	 * Enables modded chat.
	 */
	public void enable() {
		if (!this.channelMap.containsKey("*")) {
			this.channelMap.put("*", new ChatChannel("*"));
			this.channelMap.get("*").active = true;
		}

		if (firstRun) {
			firstRun = false;
			return;
		}

		this.serverDataLock.tryAcquire();
		this.updateChanDataPath(false);
		serverSettings.updateForServer();
		filterSettings.updateForServer();
		this.reloadServerData();
		this.reloadSettingsData(false);
		if (serverSettings.serverIP.length() > 0)
			this.loadPMPatterns();
		this.serverDataLock.release();

		if (generalSettings.saveChatLog.getValue()
				&& serverSettings.serverIP != null) {
			TabbyChatUtils.logChat(
					"\nBEGIN CHAT LOGGING"
							+ " -- "
							+ (new SimpleDateFormat()).format(Calendar
									.getInstance().getTime()), null);
		}
	}

	/**
	 * 
	 * @return true when mod is enabled
	 */
	public boolean enabled() {
		return generalSettings.tabbyChatEnable.getValue();
	}

	/**
	 * 
	 */
	protected void finalize() {
		this.storeChannelData();
	}

	/**
	 * 
	 * @return
	 */
	public List<String> getActive() {
		int n = this.channelMap.size();
		List<String> actives = new ArrayList<String>(n);

		for (ChatChannel chan : this.channelMap.values()) {
			if (chan.active)
				actives.add(chan.getTitle());
		}
		return actives;
	}

	/**
	 * 
	 * @return
	 */
	private String getCleanTimeStamp() {
		return StringUtils.stripControlCodes(this.getTimeStamp());
	}

	/**
	 * 
	 * @return the current time and date
	 */
	private String getTimeStamp() {
		return generalSettings.timeStamp.format(Calendar.getInstance()
				.getTime());
	}

	/**
	 * 
	 */
	protected void loadChannelData() {
		LinkedHashMap<String, ChatChannel> importData = null;
		if (!chanDataFile.exists())
			return;

		FileInputStream cFileStream = null;
		BufferedInputStream cBuffStream = null;
		ObjectInputStream cObjStream = null;
		try {
			cFileStream = new FileInputStream(chanDataFile);
			cBuffStream = new BufferedInputStream(cFileStream);
			cObjStream = new ObjectInputStream(cBuffStream);
			importData = (LinkedHashMap<String, ChatChannel>) cObjStream
					.readObject();
			cObjStream.close();
			cBuffStream.close();
		} catch (Exception e) {
			printErr("Unable to read channel data file : '"
					+ e.getLocalizedMessage() + "' : " + e.toString());
			return;
		}

		if (importData == null)
			return;
		int oldIDs = 0;
		try {
			for (Map.Entry<String, ChatChannel> chan : importData.entrySet()) {
				if (chan.getKey().contentEquals("TabbyChat"))
					continue;
				ChatChannel _new = null;
				if (!this.channelMap.containsKey(chan.getKey())) {
					_new = new ChatChannel(chan.getKey());
					_new.chanID = chan.getValue().chanID;
					this.channelMap.put(_new.getTitle(), _new);
				} else {
					_new = this.channelMap.get(chan.getKey());
				}
				_new.setAlias(chan.getValue().getAlias());
				_new.active = chan.getValue().active;
				_new.notificationsOn = chan.getValue().notificationsOn;
				_new.hidePrefix = chan.getValue().hidePrefix;
				_new.cmdPrefix = chan.getValue().cmdPrefix;
				this.addToChannel(
						chan.getKey(),
						new TCChatLine(-1, new ChatComponentText(
								"-- chat history from "
										+ (new SimpleDateFormat())
												.format(chanDataFile
														.lastModified())), 0,
								true), true);
				_new.importOldChat(chan.getValue());
				oldIDs++;
			}
		} catch (ClassCastException e) {
			TabbyChat
					.printMessageToChat("Unable to load channel history data due to upgrade (sorry!)");
		}
		ChatChannel.nextID = 3600 + oldIDs;
		this.resetDisplayedChat();
	}

	/**
	 * 
	 */
	protected void loadPatterns() {
		ChannelDelimEnum delims = (ChannelDelimEnum) serverSettings.delimiterChars
				.getValue();
		String colCode = "";
		String fmtCode = "";
		if (serverSettings.delimColorBool.getValue())
			colCode = ((ColorCodeEnum) serverSettings.delimColorCode.getValue())
					.toCode();
		if (serverSettings.delimFormatBool.getValue())
			fmtCode = ((FormatCodeEnum) serverSettings.delimFormatCode
					.getValue()).toCode();

		String frmt = colCode + fmtCode;

		if (((ColorCodeEnum) serverSettings.delimColorCode.getValue())
				.toString().equals("White")) {
			frmt = "(" + colCode + ")?" + fmtCode;
		} else if (frmt.length() > 7)
			frmt = "[" + frmt + "]{2}";
		if (frmt.length() > 0)
			frmt = "(?i:" + frmt + ")";
		if (frmt.length() == 0)
			frmt = "(?i:\u00A7[0-9A-FK-OR])*";

		this.chatChannelPatternDirty = Pattern.compile("^(\u00A7r)?" + frmt
				+ "\\" + delims.open() + "([\\p{L}0-9_\u00A7]+)\\"
				+ delims.close());
		this.chatChannelPatternClean = Pattern.compile("^" + "\\"
				+ delims.open() + "([\\p{L}0-9_]{1,"
				+ this.advancedSettings.maxLengthChannelName.getValue()
				+ "})\\" + delims.close());
	}

	/**
	 * 
	 */
	protected void loadPMPatterns() {
		StringBuilder toMePM = new StringBuilder();
		StringBuilder fromMePM = new StringBuilder();
		
		// Matches '(From Player):' and '(To Player):'
		toMePM.append("|^\\(From ([\\p{L}\\p{N}_]{3,16})[ ]\\)?:");
		fromMePM.append("|^\\(To ([\\p{L}\\p{N}_]{3,16})[ ]\\)?:");

		// Matches '[Player -> me]' and '[me -> Player]'
		toMePM.append("^\\[([\\p{L}\\p{N}_]{3,16})[ ]?\\-\\>[ ]?me\\]");
		fromMePM.append("^\\[me[ ]?\\-\\>[ ]?([\\p{L}\\p{N}_]{3,16})\\]");

		// Matches 'From Player' and 'To Player'
		toMePM.append("|^From ([\\p{L}\\p{N}_]{3,16})[ ]?:");
		fromMePM.append("|^To ([\\p{L}\\p{N}_]{3,16})[ ]?:");

		// Matches 'Player whispers to you' and 'You whisper to Player'
		toMePM.append("|^([\\p{L}\\p{N}_]{3,16}) whispers to you:");
		fromMePM.append("|^You whisper to ([\\p{L}\\p{N}_]{3,16}):");

		if (mc.thePlayer != null && mc.thePlayer.getCommandSenderName() != null) {
			String me = mc.thePlayer.getCommandSenderName();

			// Matches '[Player->Player1]' and '[Player1->Player]'
			toMePM.append("|^\\[([\\p{L}\\p{N}_]{3,16})[ ]?\\-\\>[ ]?")
					.append(me).append("\\]");
			fromMePM.append("|^\\[").append(me)
					.append("[ ]?\\-\\>[ ]?([\\p{L}\\p{N}_]{3,16})\\]");
		}

		this.chatPMtoMePattern = Pattern.compile(toMePM.toString());
		this.chatPMfromMePattern = Pattern.compile(fromMePM.toString());
	}

	/**
	 * 
	 * @param _gui
	 * @param _tick
	 */
	public void pollForUnread(Gui _gui, int _tick) {
		int _opacity = 0;
		int tickdiff = 50;

		this.lastChatReadLock.lock();
		try {
			if (this.lastChat != null && this.lastChat.size() > 0)
				tickdiff = _tick - this.lastChat.get(0).getUpdatedCounter();
		} finally {
			this.lastChatReadLock.unlock();
		}

		if (tickdiff < 50) {
			float var6 = TabbyChat.mc.gameSettings.chatOpacity * 0.9F + 0.1F;
			double var10 = tickdiff / 50.0D;
			var10 = 1.0D - var10;
			var10 *= 10.0D;
			if (var10 < 0.0D)
				var10 = 0.0D;
			if (var10 > 1.0D)
				var10 = 1.0D;

			var10 *= var10;
			_opacity = (int) (255.0D * var10);
			_opacity = (int) (_opacity * var6);
			if (_opacity <= 3)
				return;
			ChatBox.updateTabs(this.channelMap);

			for (ChatChannel chan : this.channelMap.values()) {
				if (chan.unread && chan.notificationsOn)
					chan.unreadNotify(_gui, _opacity);
			}
		}
	}

	/**
	 * 
	 * @param theChat
	 */
	public void processChat(List<TCChatLine> theChat) {
		if (this.serverDataLock.availablePermits() == 0) {
			this.serverDataLock.acquireUninterruptibly();
			this.serverDataLock.release();
		}
		if (theChat.isEmpty())
			return;

		List<TCChatLine> resultChatLine;
		List<String> toTabs = new ArrayList<String>(20);
		List<String> filterTabs = new ArrayList<String>(20);
		String channelTab = null;
		String pmTab = null;
		toTabs.add("*");

		
		ComponentList raw = TabbyChatUtils.chatLinesToComponent(theChat);
		ComponentList filtered = this.processChatForFilters(raw, filterTabs);
		if (generalSettings.saveChatLog.getValue()
				&& !generalSettings.splitChatLog.getValue())
			TabbyChatUtils.logChat(this.getCleanTimeStamp() + raw.getUnformattedText(), null);

		if (filtered != null) {
			ChatChannel tab = null;
			if (serverSettings.autoChannelSearch.getValue())
				channelTab = this.processChatForChannels(raw);
			if (channelTab == null) {
				if (serverSettings.autoPMSearch.getValue()) {
					pmTab = this.processChatForPMs(raw);
					tab = new ChatChannel(pmTab);
					if (generalSettings.saveChatLog.getValue()
							&& generalSettings.splitChatLog.getValue())
						TabbyChatUtils.logChat(this.getCleanTimeStamp()
								+ raw.getUnformattedText(), tab);
				}
			} else {
				toTabs.add(channelTab);
				tab = new ChatChannel(channelTab);
				if (generalSettings.saveChatLog.getValue()
						&& generalSettings.splitChatLog.getValue())
					TabbyChatUtils.logChat(this.getCleanTimeStamp() + raw.getUnformattedText(), tab);
			}
			toTabs.addAll(filterTabs);
		} else {
			filtered = raw;
		}
		resultChatLine = TabbyChatUtils.componentToChatLines(theChat.get(0)
				.getUpdatedCounter(), filtered, theChat.get(0).getChatLineID(),
				theChat.get(0).statusMsg);
		
		this.addOptionalTimeStamp(resultChatLine);

		HashSet<String> tabSet = new HashSet<String>(toTabs);
		List<String> activeTabs = this.getActive();

		boolean visible = false;

		if (pmTab != null) {
			if (!this.channelMap.containsKey(pmTab)) {
				ChatChannel pm = new ChatChannel(pmTab);
				pm.cmdPrefix = "/msg " + pmTab;
				this.channelMap.put(pmTab, pm);
				this.addToChannel(pmTab, resultChatLine, false);
				if (mc.currentScreen instanceof GuiChatTC) {
					((GuiChatTC) mc.currentScreen).addChannelLive(pm);
				}
			} else if (this.channelMap.containsKey(pmTab)) {
				if (activeTabs.contains(pmTab))
					visible = true;
				this.addToChannel(pmTab, resultChatLine, visible);
			}
		}

		if (!visible) {
			Set<String> tabUnion = (Set<String>) tabSet.clone();
			tabUnion.retainAll(activeTabs);
			if (tabUnion.size() > 0)
				visible = true;
		}

		Iterator<String> tabIter = tabSet.iterator();
		while (tabIter.hasNext()) {
			String tab = tabIter.next();
			this.addToChannel(tab, resultChatLine, visible);
		}

		this.lastChatWriteLock.lock();
		try {
			if (generalSettings.groupSpam.getValue() && activeTabs.size() > 0) {
				if (toTabs.contains(activeTabs.get(0)))
					this.lastChat = this.channelMap.get(activeTabs.get(0))
							.getChatLogSublistCopy(0, resultChatLine.size());
				else
					this.lastChat = resultChatLine;
			} else
				this.lastChat = resultChatLine;
		} finally {
			this.lastChatWriteLock.unlock();
		}
		this.lastChatReadLock.lock();
		try {
			if (visible) {
				if (generalSettings.groupSpam.getValue()
						&& this.channelMap.get(activeTabs.get(0)).hasSpam) {
					gnc.setChatLines(0,
							new ArrayList<TCChatLine>(this.lastChat));
				} else {
					gnc.addChatLines(0,
							new ArrayList<TCChatLine>(this.lastChat));
				}
			}
		} finally {
			this.lastChatReadLock.unlock();
		}
	}

	/**
	 * 
	 * @param clean
	 * @param raw
	 * @return
	 */
	private String processChatForChannels(ComponentList raw) {
		Matcher findChannelClean = this.chatChannelPatternClean.matcher(raw.getUnformattedText());
		Matcher findChannelDirty = this.chatChannelPatternDirty.matcher(raw.getFormattedText());
		boolean dirtyCheck = (!serverSettings.delimColorBool.getValue() && !serverSettings.delimFormatBool
				.getValue()) ? true : findChannelDirty.find();
		if (findChannelClean.find() && dirtyCheck)
			return findChannelClean.group(1);
		return null;
	}

	/**
	 * 
	 * @param raw
	 * @param destinations
	 * @return
	 */
	private ComponentList processChatForFilters(ComponentList raw, List<String> destinations) {
		// TODO
		if(raw == null)
			return null;
		
		// Iterate through defined filters
		Entry<Integer, TCChatFilter> iFilter = filterSettings.filterMap.firstEntry();
		while (iFilter != null) {
			for(int i = 0; i < raw.size(); i++){
				IChatComponent chat = raw.get(i);
				if (iFilter.getValue().applyFilterToDirtyChat(chat)) {
					if (iFilter.getValue().removeMatches) {
						return ComponentList.newInstance();
					}
					if (iFilter.getValue().highlightBool) {
						int[] lastMatch = iFilter.getValue().getLastMatch();
						for (int i1 = 0; i1 < lastMatch.length; i1+=2) {
							int start = lastMatch[i1];
							int end = lastMatch[i1+1];
							
							IChatComponent chat1 = ChatComponentUtils.subComponent(chat, 0, start);
							IChatComponent chat2 = ChatComponentUtils.subComponent(chat, start, end);
							IChatComponent chat3 = ChatComponentUtils.subComponent(chat, end);

							ChatStyle style = chat2.getChatStyle();
							style.setColor(iFilter.getValue().highlightColor.toVanilla());

							switch (iFilter.getValue().highlightFormat) {
							case BOLD:
								style.setBold(true);
								break;
							case ITALIC:
								style.setItalic(true);
								break;
							case STRIKED:
								style.setStrikethrough(true);
								break;
							case UNDERLINE:
								style.setUnderlined(true);
								break;
							case MAGIC:
								style.setObfuscated(true);
								break;
							case DEFAULT:
							default:
								break;
							}

							 raw.set(i, chat1.appendSibling(chat2).appendSibling(chat3));
						}
					}
					if (iFilter.getValue().sendToTabBool) {
						if (iFilter.getValue().sendToAllTabs) {
							for (ChatChannel chan : this.channelMap.values()) {
								destinations.add(chan.getTitle());
							}
							continue;
						} else {
							String destTab = iFilter.getValue().getTabName();
							if (destTab != null && destTab.length() > 0
									&& !destinations.contains(destTab)) {
								destinations.add(destTab);
							}
						}
					}
					if (iFilter.getValue().audioNotificationBool) {
						iFilter.getValue().audioNotification();
					}
				}
			}
			iFilter = filterSettings.filterMap.higherEntry(iFilter.getKey());
		}
		return raw;
	}

	/**
	 * Processes chat for PMs
	 * 
	 * @param raw
	 * @return
	 */
	private String processChatForPMs(ComponentList raw2) {
		IChatComponent raw = raw2.get(0);
		if (this.chatPMtoMePattern != null) {
			Matcher findPMtoMe = this.chatPMtoMePattern.matcher(raw.getUnformattedText());
			if (findPMtoMe.find()) {
				for (int i = 1; i <= findPMtoMe.groupCount(); i++) {
					if (findPMtoMe.group(i) != null)
						return findPMtoMe.group(i);
				}
			} else if (this.chatPMfromMePattern != null) {
				Matcher findPMfromMe = this.chatPMfromMePattern.matcher(raw.getUnformattedText());
				if (findPMfromMe.find()) {
					for (int i = 1; i <= findPMfromMe.groupCount(); i++) {
						if (findPMfromMe.group(i) != null)
							return findPMfromMe.group(i);
					}
				}
			}
		}
		return null;
	}

	/**
	 * Reloads server settings
	 */
	private void reloadServerData() {
		serverSettings.loadSettingsFile();
		filterSettings.loadSettingsFile();
		this.loadChannelData();
	}

	/**
	 * 
	 * @param withSave
	 */
	public void reloadSettingsData(boolean withSave) {
		this.updateDefaults();
		this.loadPatterns();
		this.updateFilters();
		if (withSave)
			this.storeChannelData();
	}

	/**
	 * Removes tab from chat
	 * 
	 * @param _name
	 *            tab to remove
	 */
	public void removeTab(String _name) {
		this.channelMap.remove(_name);
	}

	/**
	 * 
	 */
	public void resetDisplayedChat() {
		gnc.clearChatLines();
		List<String> actives = this.getActive();
		if (actives.size() < 1)
			return;
		gnc.addChatLines(this.channelMap.get(actives.get(0)));
		int n = actives.size();
		for (int i = 1; i < n; i++) {
			gnc.mergeChatLines(this.channelMap.get(actives.get(i)));
		}
	}

	/**
	 * Checks for spam
	 * 
	 * @param theChan
	 * @param lastChat
	 */
	private void spamCheck(ChatChannel theChan, List<TCChatLine> lastChat) {
		String oldChat = "";
		String newChat = "";
		if (theChan.getChatLogSize() < lastChat.size() || lastChat.size() == 0) {
			theChan.hasSpam = false;
			theChan.spamCount = 1;
			return;
		}
		int _size = lastChat.size();
		for (int i = 0; i < _size; i++) {
			if (lastChat.get(i).getChatLineString() == null
					|| theChan.getChatLine(i).getChatLineString() == null)
				continue;
			newChat = newChat
					+ lastChat.get(i).getChatLineString().getUnformattedText();
			oldChat = theChan.getChatLine(i).getChatLineString()
					.getUnformattedText()
					+ oldChat;
		}
		if (theChan.hasSpam) {
			oldChat = oldChat.substring(0, oldChat.length() - 4
					- Integer.toString(theChan.spamCount).length());
		}
		if (oldChat.equals(newChat)) {
			theChan.hasSpam = true;
			theChan.spamCount++;
			for (int i = 1; i < _size; i++) {
				TCChatLine line = new TCChatLine(lastChat.get(0)
						.getUpdatedCounter(), lastChat.get(
						lastChat.size() - i - 1).getChatLineString(), lastChat
						.get(0).getChatLineID());
				line.timeStamp = this.getTimeStamp();
				theChan.setChatLogLine(i, line);
			}
			TCChatLine line = new TCChatLine(lastChat.get(0)
					.getUpdatedCounter(), lastChat.get(lastChat.size() - 1)
					.getChatLineString().createCopy()
					.appendText(" [" + theChan.spamCount + "x]"), lastChat.get(
					0).getChatLineID());
			line.timeStamp = this.getTimeStamp();
			theChan.setChatLogLine(0, line);
		} else {
			theChan.hasSpam = false;
			theChan.spamCount = 1;
		}
	}

	/**
	 * 
	 */
	public void storeChannelData() {
		if (chanDataFile == null)
			return;
		if (!chanDataFile.getParentFile().exists())
			chanDataFile.getParentFile().mkdirs();

		FileOutputStream cFileStream = null;
		BufferedOutputStream cBuffStream = null;
		ObjectOutputStream cObjStream = null;
		try {
			cFileStream = new FileOutputStream(chanDataFile);
			cBuffStream = new BufferedOutputStream(cFileStream);
			cObjStream = new ObjectOutputStream(cBuffStream);
			cObjStream.writeObject(instance.channelMap);
			cObjStream.flush();
		} catch (Exception e) {
			printErr("Unable to write channel data to file : '"
					+ e.getLocalizedMessage() + "' : " + e.toString());
		} finally {
			try {
				cObjStream.close();
				cBuffStream.close();
			} catch (Exception e) {
			}
		}
	}

	/**
	 * 
	 * @param make
	 */
	private void updateChanDataPath(boolean make) {
		String pName = "";
		if (mc.thePlayer != null && mc.thePlayer.getCommandSenderName() != null)
			pName = mc.thePlayer.getCommandSenderName();
		File parentDir = TabbyChatUtils.getServerDir();
		if (make && !parentDir.exists())
			parentDir.mkdirs();
		chanDataFile = new File(parentDir, pName + "_chanData.ser");
	}

	/**
	 * Updates general settings
	 */
	protected void updateDefaults() {
		if (!TabbyChat.generalSettings.tabbyChatEnable.getValue())
			return;
		List<String> dList = new ArrayList<String>(serverSettings.defaultChanList);
		int ind;
		for (ChatChannel chan : this.channelMap.values()) {
			ind = dList.indexOf(chan.getTitle());
			if (ind >= 0)
				dList.remove(ind);
		}

		for (String defChan : dList) {
			if (defChan.length() > 0)
				this.channelMap.put(defChan, new ChatChannel(defChan));
		}
	}

	/**
	 * Updates filter settings
	 */
	protected void updateFilters() {
		if (!generalSettings.tabbyChatEnable.getValue())
			return;
		if (filterSettings.filterMap.size() == 0)
			return;

		Entry<Integer, TCChatFilter> iFilter = filterSettings.filterMap
				.firstEntry();
		String newName;
		while (iFilter != null) {
			newName = iFilter.getValue().sendToTabName;
			if (iFilter.getValue().sendToTabBool
					&& !iFilter.getValue().sendToAllTabs
					&& !this.channelMap.containsKey(newName)
					&& !newName.startsWith("%")) {
				this.channelMap.put(newName, new ChatChannel(newName));
			}
			iFilter = filterSettings.filterMap.higherEntry(iFilter.getKey());
		}
	}
}
