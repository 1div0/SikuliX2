/*
 * Copyright (c) 2016 - sikulix.com - MIT license
 */

package com.sikulix.core;

import com.sikulix.api.Element;
import com.sikulix.api.Event;
import com.sikulix.api.Handler;
import com.sikulix.util.Settings;
import com.sikulix.util.animation.Animator;
import com.sikulix.util.animation.AnimatorOutQuarticEase;
import com.sikulix.util.animation.AnimatorTimeBased;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

public class  Device {

  protected static SXLog log = SX.getLogger("SX.Device");

  protected static Device device = null;
  protected static String deviceName = "Device";

  protected static boolean isMouse = false;
  protected boolean isKeys = false;
  protected boolean isLocalScreen = false;
  protected boolean isOtherScreen = false;


  private static boolean inUse = false;
  private static boolean keep = false;
  protected static Object deviceOwner = null;
  private static boolean blocked = false;
  private static boolean suspended = false;
  private static Object synchObject = new Object();

  //TODO MouseButtons
  private static boolean areExtraMouseButtonsEnabled = false;
  private static int numberOfButtons = 0;
  public static void getMouseSetup() {
    areExtraMouseButtonsEnabled = Toolkit.getDefaultToolkit().areExtraMouseButtonsEnabled();
    numberOfButtons = MouseInfo.getNumberOfButtons();
    int maskForButton1 = InputEvent.getMaskForButton(1);
  }


  //<editor-fold desc="*** Construction">
  private Device() {
  }
  //</editor-fold>

  //<editor-fold desc="*** Callback">
  private static Event callback = null;
  private static boolean shouldRunCallback = false;
  private static boolean shouldTerminate = false;

  public static void setShouldTerminate() {
    shouldTerminate = true;
    log.debug("setShouldTerminate: request issued");
  }

  public static boolean isShouldRunCallback() {
    return shouldRunCallback;
  }

  public static void setShouldRunCallback(boolean shouldRun) {
    shouldRunCallback = shouldRun;
  }

  private static void checkShouldRunCallback() {
    if (shouldRunCallback && callback != null) {
      callback.handle();
      if (shouldTerminate) {
        shouldTerminate = false;
        throw new AssertionError("aborted by Sikulix.GenericDeviceCallBack");
      }
    }
  }

  /**
   * set a callback function for handling Device events <br>
   * in case of event the user provided callBack.happened is called
   *
   * @param handler
   */
  public static void setCallback(Handler handler) {
    callback = new Event(handler);
  }
  //</editor-fold>

  //<editor-fold desc="*** get state">
  protected static boolean isInUse() {
    return inUse;
  }

  protected static boolean isSuspended() {
    return suspended;
  }

  protected static boolean isBlocked() {
    return blocked;
  }

  private static boolean isNotLocal(Object owner) {
    if (owner instanceof SXElement) {
      return ((SXElement) owner).isSpecial();
    }
    return false;
  }
  //</editor-fold>

  //<editor-fold desc="*** block globally">

  /**
   * to block the device globally <br>
   * only the contained device methods without deviceOwner will be granted
   *
   * @return success
   */
  public static boolean block() {
    return block(null);
  }

  /**
   * to block the device globally for the given deviceOwner <br>
   * only the contained mouse methods having the same deviceOwner will be granted
   *
   * @param owner Object
   * @return success
   */
  public static boolean block(Object owner) {
    if (use(owner)) {
      blocked = true;
      return true;
    } else {
      return false;
    }
  }

  /**
   * free the mouse globally after a block()
   *
   * @return success (false means: not blocked currently)
   */
  public static boolean unblock() {
    return unblock(null);
  }

  /**
   * free the mouse globally for this deviceOwner after a block(deviceOwner)
   *
   * @param ownerGiven Object
   * @return success (false means: not blocked currently for this deviceOwner)
   */
  public static boolean unblock(Object ownerGiven) {
    if (ownerGiven == null) {
      ownerGiven = device;
    } else if (isNotLocal(ownerGiven)) {
      return false;
    }
    if (blocked && deviceOwner == ownerGiven) {
      blocked = false;
      let(ownerGiven);
      return true;
    }
    return false;
  }
  //</editor-fold>

  //<editor-fold desc="*** coordinate usage">
  public static boolean use() {
    return use(null);
  }

  public static synchronized boolean use(Object owner) {
    if (owner == null) {
      owner = device;
    } else if (isNotLocal(owner)) {
      return false;
    }
    if ((blocked || inUse) && deviceOwner == owner) {
      return true;
    }
    while (inUse) {
      try {
        synchObject.wait();
      } catch (InterruptedException e) {
      }
    }
    if (!inUse) {
      inUse = true;
      if (isMouse) {
        checkLastPos();
        checkShouldRunCallback();
        if (shouldTerminate) {
          shouldTerminate = false;
          throw new AssertionError(String.format("Device: %s: termination after return from callback", deviceName));
        }
      }
      keep = false;
      log.trace("%s: use start: %s", deviceName, owner);
      return true;
    }
    log.error("use synch problem at start: %s", owner);
    return false;
  }

  public static synchronized boolean keep(Object ownerGiven) {
    if (isNotLocal(ownerGiven)) {
      return false;
    }
    if (inUse && deviceOwner == ownerGiven) {
      keep = true;
      log.trace("%s: use keep: %s", deviceName, ownerGiven);
      return true;
    }
    return false;
  }

  public static boolean let() {
    return let(null);
  }

  public static synchronized boolean let(Object owner) {
    if (isNotLocal(owner)) {
      return false;
    }
    if (inUse && deviceOwner == owner) {
      if (keep) {
        keep = false;
        return true;
      }
      setLastPos();
      inUse = false;
      deviceOwner = null;
      synchObject.notify();
      log.trace("%s: use stop: %s", deviceName, deviceOwner);
      return true;
    }
    return false;
  }
  //</editor-fold>

  public static void delay(int time) {
    if (time == 0) {
      return;
    }
    if (time < 60) {
      time = time * 1000;
    }
    try {
      Thread.sleep(time);
    } catch (InterruptedException e) {
    }
  }

  //<editor-fold desc="*** mouse pointer location ***">
  private static Element lastPos = null;

  public static void setLastPos() {
    lastPos = Element.at();
  }

  public static void checkLastPos() {
    if (lastPos == null) {
      return;
    }
    Element pos = Element.at();
    if (pos != null && (lastPos.x != pos.x || lastPos.y != pos.y)) {
      log.debug("moved externally: now (%d,%d) was (%d,%d) (movedAction %d)",
              pos.x, pos.y, lastPos.x, lastPos.y, movedAction);
      if (movedAction > 0) {
        if (MOVEDHIGHLIGHT) {
          showMousePos(pos.getPoint());
        }
      }
      if (movedAction == MOVEDPAUSE) {
        while (pos.x > 0 && pos.y > 0) {
          delay(500);
          pos = Element.at();
          if (MOVEDHIGHLIGHT) {
            showMousePos(pos.getPoint());
          }
        }
        if (pos.x < 1) {
          return;
        }
        SX.terminate(1, "Terminating in MouseMovedResponse = Pause");
      }
      if (movedAction == MOVEDCALLBACK) {
//TODO implement 3
//        if (mouseMovedCallback != null) {
//          mouseMovedCallback.happened(new ObserveEvent(ObserveEvent.Type.GENERIC, lastPos, new Location(pos)));
//          if (shouldTerminate) {
//            shouldTerminate = false;
//            throw new AssertionError("aborted by Sikulix.MouseMovedCallBack");
//          }
//        }
      }
    }
  }

  private static void showMousePos(Point pos) {
    //TODO implement showMousePos (Visual.highlight)
//    Location lPos = new Location(pos);
//    Region inner = lPos.grow(20).highlight();
//    delay(500);
//    lPos.grow(40).highlight(1);
//    delay(500);
//    inner.highlight();
  }
  //</editor-fold>

  //<editor-fold desc="*** mouse moved reaction ***">
  public static final int MOVEDIGNORE = 0;
  public static final int MOVEDSHOW = 1;
  public static final int MOVEDPAUSE = 2;
  public static final int MOVEDCALLBACK = 3;

  private static int movedAction = MOVEDIGNORE;
  private static Event movedHandler;
  private static boolean MOVEDHIGHLIGHT = true;

  /**
   * current setting what to do if mouse is moved outside Sikuli's mouse protection
   *
   * @return current setting see {@link #setMovedAction(int)}
   */
  public int getMovedAction() {
    return movedAction;
  }

  /**
   * what to do if mouse is moved outside Sikuli's mouse protection <br>
   * - Mouse.MOVEDIGNORE (0) ignore it (default) <br>
   * - Mouse.MOVEDSHOW (1) show and ignore it <br>
   * - Mouse.MOVEDPAUSE (2) show it and pause until user says continue <br>
   * - Mouse.MOVEDCALLBACK (3) visit a given callback {@link #setMovedCallback(Handler)} <br>
   *
   * @param movedAction value
   */
  public int setMovedAction(int movedAction) {
    if (movedAction >= MOVEDIGNORE && movedAction <= MOVEDCALLBACK) {
      this.movedAction = movedAction;
      setCallback(null);
      log.debug("setMovedAction: %d", this.movedAction);
    } else {
      this.movedAction = MOVEDIGNORE;
      log.error("setMovedAction: %d invalid - setting to MOVEDIGNORE", movedAction);
    }
    return this.movedAction;
  }

  /**
   * what to do if mouse is moved outside Sikuli's mouse protection <br>
   * in case of event the user provided callBack.generic is called
   *
   * @param handler Handler
   */
  public static void setMovedCallback(Handler handler) {
      movedAction = MOVEDCALLBACK;
      movedHandler = new Event(handler);
  }

  /**
   * @param state
   */
  public void setMovedHighlight(boolean state) {
    MOVEDHIGHLIGHT = state;
  }

  /**
   * check if mouse was moved since last mouse action
   *
   * @return true/false
   */
  public static boolean hasMoved() {
    Element pos = Element.at();
    if (lastPos.x != pos.x || lastPos.y != pos.y) {
      return true;
    }
    return false;
  }
  //</editor-fold>

  //<editor-fold desc="*** click ***">
  public static final int LEFT = InputEvent.BUTTON1_MASK;
  public static final int MIDDLE = InputEvent.BUTTON2_MASK;
  public static final int RIGHT = InputEvent.BUTTON3_MASK;

  /**
   * to click (left, right, middle - single or double) at the given location using the given button
   * only useable for local screens
   * <p>
   * timing parameters: <br>
   * - one value <br>
   * &lt; 0 wait before mouse down <br>
   * &gt; 0 wait after mouse up <br>
   * - 2 or 3 values 1st wait before mouse down <br>
   * 2nd wait after mouse up <br>
   * 3rd inner wait (milli secs, cut to 1000): pause between mouse down and up (Settings.ClickDelay)
   * <p>
   * wait before and after: &gt; 59 taken as milli secs - &lt; are seconds
   *
   * @param loc    where to click
   * @param action L,R,M left, right, middle - D means double click
   * @param args   timing parameters
   * @return the location
   */
  public static Element click(Element loc, String action, Integer... args) {
    if (isSuspended() || loc.isSpecial()) {
      return null;
    }
    boolean clickDouble = false;
    action = action.toUpperCase();
    if (action.contains("D")) {
      clickDouble = true;
    }
    int buttons = 0;
    if (action.contains("L")) {
      buttons += LEFT;
    }
    if (action.contains("M")) {
      buttons += MIDDLE;
    }
    if (action.contains("R")) {
      buttons += RIGHT;
    }
    if (buttons == 0) {
      buttons = LEFT;
    }
    int beforeWait = 0;
    int innerWait = 0;
    int afterWait = 0;
    if (args.length > 0) {
      if (args.length == 1) {
        if (args[0] < 0) {
          beforeWait = -args[0];
        } else {
          afterWait = args[0];
        }
      }
      beforeWait = args[0];
      if (args.length > 1) {
        afterWait = args[1];
        if (args.length > 2) {
          innerWait = args[2];
        }
      }
    }
    use();
    Device.delay(beforeWait);
    Settings.ClickDelay = innerWait / 1000;
    click(loc, buttons, 0, clickDouble, null);
    Device.delay(afterWait);
    let();
    return loc;
  }

  private static int click(Element loc, int buttons, Integer modifiers, boolean dblClick, Element vis) {
    if (modifiers == null) {
      modifiers = 0;
    }
    boolean shouldMove = true;
    if (loc == null) {
      shouldMove = false;
      loc = Element.at();
    }
    IRobot robot = loc.getDeviceRobot();
    if (robot == null) {
      return 0;
    }
    Point pLoc = loc.getPoint();
    use(vis);
    log.info("%s", getClickMsg(loc, buttons, modifiers, dblClick));
    if (shouldMove) {
      smoothMove(pLoc, robot);
    }
    robot.pressModifiers(modifiers);
    int pause = Settings.ClickDelay > 1 ? 1 : (int) (Settings.ClickDelay * 1000);
    Settings.ClickDelay = 0.0;
    if (dblClick) {
      robot.mouseDown(buttons);
      robot.mouseUp(buttons);
      robot.mouseDown(buttons);
      robot.mouseUp(buttons);
    } else {
      robot.mouseDown(buttons);
      robot.delay(pause);
      robot.mouseUp(buttons);
    }
    robot.releaseModifiers(modifiers);
    robot.waitForIdle();
    let(vis);
    return 1;
  }

  private static String getClickMsg(Element loc, int buttons, int modifiers, boolean dblClick) {
    String msg = "";
    if (modifiers != 0) {
      msg += KeyEvent.getKeyModifiersText(modifiers) + "+";
    }
    if (buttons == InputEvent.BUTTON1_MASK && !dblClick) {
      msg += "CLICK";
    }
    if (buttons == InputEvent.BUTTON1_MASK && dblClick) {
      msg += "DOUBLE CLICK";
    }
    if (buttons == InputEvent.BUTTON3_MASK) {
      msg += "RIGHT CLICK";
    } else if (buttons == InputEvent.BUTTON2_MASK) {
      msg += "MID CLICK";
    }
    msg += " on " + loc;
    return msg;
  }
  //</editor-fold>

  //<editor-fold desc="*** move ***">

  /**
   * move the mouse to the given location
   *
   * @param vis Location
   * @return 1 for success, 0 otherwise
   */
  public static int move(Element vis) {
    return move(vis.getTarget(), null);
  }

  /**
   * move the mouse from the current position to the offset position given by the parameters
   *
   * @param xoff horizontal offset (&lt; 0 left, &gt; 0 right)
   * @param yoff vertical offset (&lt; 0 up, &gt; 0 down)
   * @return 1 for success, 0 otherwise
   */
  public static int move(int xoff, int yoff) {
    return move(Element.at().offset(xoff, yoff));
  }

  public static int move(Element loc, Element vis) {
    if (isSuspended()) {
      return 0;
    }
    Point pLoc = null;
    if (loc != null) {
      pLoc = loc.getPoint();
      IRobot robot = loc.getDeviceRobot();
      if (robot == null) {
        return 0;
      }
      use(vis);
      smoothMove(pLoc, robot);
      let(vis);
      return 1;
    }
    return 0;
  }

  public static void smoothMove(Point dest, IRobot robot) {
    smoothMove(new Point(Element.at().x, Element.at().y), dest, (long) (Settings.MoveMouseDelay * 1000L), robot);
  }

  public static void smoothMove(Point src, Point dest, long ms, IRobot robot) {
    int x = dest.x;
    int y = dest.y;
    log.trace("smoothMove (%.1f): (%d, %d) to (%d, %d)", Settings.MoveMouseDelay, src.x, src.y, x, y);
    if (ms == 0) {
      robot.mouseMove(x, y);
    } else {
      Animator aniX = new AnimatorTimeBased(
              new AnimatorOutQuarticEase(src.x, dest.x, ms));
      Animator aniY = new AnimatorTimeBased(
              new AnimatorOutQuarticEase(src.y, dest.y, ms));
      while (aniX.running()) {
        x = (int) aniX.step();
        y = (int) aniY.step();
        robot.mouseMove(x, y);
      }
    }
    robot.waitForIdle();
    PointerInfo mp = MouseInfo.getPointerInfo();
    Point pc;
    if (mp == null) {
      log.error("RobotDesktop: checkMousePosition: MouseInfo.getPointerInfo invalid after move to (%d, %d)", x, y);
    } else {
      pc = mp.getLocation();
      if (pc.x != x || pc.y != y) {
        log.error("RobotDesktop: checkMousePosition: should be (%d, %d)\nbut after move is (%d, %d)"
                        + "\nPossible cause in case you did not touch the mouse while script was running:\n"
                        + " Mouse actions are blocked generally or by the frontmost application."
                        + (SX.isWindows() ? "\nYou might try to run the SikuliX stuff as admin." : ""),
                x, y, pc.x, pc.y);
      }
    }
  }
  //</editor-fold>

  //<editor-fold desc="*** hold buttons down ***">

  /**
   * press and hold the given buttons {@link Button}
   *
   * @param buttons value
   */
  public static void down(int buttons) {
    down(buttons, null);
  }

  public static void down(int buttons, Element vis) {
    if (isSuspended()) {
      return;
    }
    use(vis);
    IRobot robot = SX.isNull(vis) ? SX.getLocalRobot() : vis.getDeviceRobot();
    robot.mouseDown(buttons);
  }
  //</editor-fold>

  //<editor-fold desc="*** release buttons ***">

  /**
   * release all buttons
   */
  public static void up() {
    up(0, null);
  }

  /**
   * release the given buttons {@link Button}
   *
   * @param buttons (0 releases all buttons)
   */
  public static void up(int buttons) {
    up(buttons, null);
  }

  public static void up(int buttons, Element vis) {
    if (isSuspended()) {
      return;
    }
    IRobot robot = SX.isNull(vis) ? SX.getLocalRobot() : vis.getDeviceRobot();
    robot.mouseUp(buttons);
    let(vis);
  }
  //</editor-fold>

  //<editor-fold desc="*** mouse wheel ***">
  public static final int WHEEL_UP = -1;
  public static final int WHEEL_DOWN = 1;
  public static final int WHEEL_STEP_DELAY = 50;

  /**
   * move mouse using mouse wheel in the given direction the given steps <br>
   * the result is system dependent
   *
   * @param direction {@link Button}
   * @param steps     value
   */
  public static void wheel(int direction, int steps) {
    wheel(direction, steps, null);
  }

  public static void wheel(int direction, int steps, Element vis) {
    wheel(direction, steps, vis, WHEEL_STEP_DELAY);
  }

  public static void wheel(int direction, int steps, Element vis, int stepDelay) {
    if (isSuspended()) {
      return;
    }
    IRobot robot = SX.isNull(vis) ? SX.getLocalRobot() : vis.getDeviceRobot();
    use(vis);
    for (int i = 0; i < steps; i++) {
      robot.mouseWheel(direction);
      robot.delay(stepDelay);
    }
    let(vis);
  }
  //</editor-fold>

  public static class Modifier {
    public static final int CTRL = InputEvent.CTRL_MASK;
    public static final int SHIFT = InputEvent.SHIFT_MASK;
    public static final int ALT = InputEvent.ALT_MASK;
    public static final int ALTGR = InputEvent.ALT_GRAPH_MASK;
    public static final int META = InputEvent.META_MASK;
    public static final int CMD = InputEvent.META_MASK;
    public static final int WIN = 64;
  }

  public static boolean hasModifier(int mods, int mkey) {
    return (mods & mkey) != 0;
  }

  public static int[] toJavaKeyCode(char character) {
    //TODO implement toJavaKeyCode
    return new int[0];
  }

  public static int toJavaKeyCodeFromText(String s) {
    //TODO implement toJavaKeyCodeFromText
    return 0;
  }
}
