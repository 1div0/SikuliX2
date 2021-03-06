/*
 * Copyright (c) 2016 - sikulix.com - MIT license
 */

package com.sikulix.core;

import com.sikulix.api.Do;
import com.sikulix.api.Element;
import com.sikulix.api.Picture;
import com.sikulix.api.Target;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.util.*;
import java.util.List;

public class Finder {

  //<editor-fold desc="housekeeping">
  private static final SXLog log = SX.getLogger("SX.Finder");

  private Element baseElement = null;
  private Mat base = new Mat();
  private Mat result = new Mat();

  private enum FindType {
    ONE, ALL
  }

  private Finder() {
  }

  public Finder(Element elem) {
    if (elem != null && elem.isValid()) {
      baseElement = elem;
      base = elem.getContent();
    } else {
      log.error("init: invalid element: %s", elem);
    }
  }

  public boolean isValid() {
    return !base.empty();
  }

  public void refreshBase() {
    base = baseElement.getContent();
  }
  //</editor-fold>

  //<editor-fold desc="find basic">
  public Element find(Element target) {
    baseElement.resetMatches();
    FindResult findResult = doFind(target, FindType.ONE);
    if (SX.isNotNull(findResult) && findResult.hasNext()) {
      baseElement.setLastMatch(findResult.next());
      return baseElement.getLastMatch();
    }
    return new Element();
  }

  public List<Element> findAll(Element target) {
    baseElement.resetMatches();
    FindResult findResult = doFind(target, FindType.ALL);
    List<Element> matches = findResult.getMatches();
    Collections.sort(matches);
    Collections.sort(matches);
    baseElement.setLastMatches(matches);
    baseElement.setLastScores(findResult.getScores());
    return matches;
  }

  private final double resizeMinFactor = 1.5;
  private final double[] resizeLevels = new double[]{1f, 0.4f};
  private int resizeMaxLevel = resizeLevels.length - 1;
  private double resizeMinSim = 0.8;
  private boolean isCheckLastSeen = false;
  private static final double downSimDiff = 0.15;

  private FindResult doFind(Element elem, FindType findType) {
    if (!elem.isTarget()) {
      return null;
    }
    log.trace("doFind: start");
    Element target = elem;
    if (target.getWantedScore() < 0) {
      target.setWantedScore(0.8);
    }
    boolean success = false;
    long begin_t = 0;
    Core.MinMaxLocResult mMinMax = null;
    FindResult findResult = null;
    if (FindType.ONE.equals(findType) && !isCheckLastSeen && SX.isOption("CheckLastSeen") && target.getLastSeen().isValid()) {
      begin_t = new Date().getTime();
      Finder lastSeenFinder = new Finder(target.getLastSeen());
      lastSeenFinder.isCheckLastSeen = true;
      target = new Target(target, target.getLastSeen().getScore() - 0.01);
      findResult = lastSeenFinder.doFind(target, FindType.ONE);
      if (findResult.hasNext()) {
        log.trace("doFind: checkLastSeen: success %d msec", new Date().getTime() - begin_t);
        return findResult;
      } else {
        log.trace("doFind: checkLastSeen: not found %d msec", new Date().getTime() - begin_t);
      }
    }
    double rfactor = 0;
    boolean downSizeFound = false;
    double downSizeScore = 0;
    double downSizeWantedScore = 0;
    if (FindType.ONE.equals(findType) && target.getResizeFactor() > resizeMinFactor) {
      // ************************************************* search in downsized
      begin_t = new Date().getTime();
      double imgFactor = target.getResizeFactor();
      Size sb, sp;
      Mat mBase = new Mat(), mPattern = new Mat();
      result = null;
      for (double factor : resizeLevels) {
        rfactor = factor * imgFactor;
        sb = new Size(base.cols() / rfactor, base.rows() / rfactor);
        sp = new Size(target.getContent().cols() / rfactor, target.getContent().rows() / rfactor);
        Imgproc.resize(base, mBase, sb, 0, 0, Imgproc.INTER_AREA);
        Imgproc.resize(target.getContent(), mPattern, sp, 0, 0, Imgproc.INTER_AREA);
        result = doFindMatch(target, mBase, mPattern);
        mMinMax = Core.minMaxLoc(result);
        downSizeWantedScore = ((int) ((target.getWantedScore() - downSimDiff) * 100)) / 100.0;
        downSizeScore = mMinMax.maxVal;
        if (downSizeScore > downSizeWantedScore) {
          downSizeFound = true;
          break;
        }
      }
      log.trace("doFind: down: %%%.2f %d msec", 100 * mMinMax.maxVal, new Date().getTime() - begin_t);
    }
    if (FindType.ONE.equals(findType) && downSizeFound) {
      // ************************************* check after downsized success
      if (base.size().equals(target.getContent().size())) {
        // trust downsized result, if images have same size
        return new FindResult(result, target);
      } else {
        int maxLocX = (int) (mMinMax.maxLoc.x * rfactor);
        int maxLocY = (int) (mMinMax.maxLoc.y * rfactor);
        begin_t = new Date().getTime();
        int margin = ((int) target.getResizeFactor()) + 1;
        Rect rectSub = new Rect(Math.max(0, maxLocX - margin), Math.max(0, maxLocY - margin),
                Math.min(target.w + 2 * margin, base.width()),
                Math.min(target.h + 2 * margin, base.height()));
        result = doFindMatch(target, base.submat(rectSub), null);
        mMinMax = Core.minMaxLoc(result);
        if (mMinMax.maxVal > target.getWantedScore()) {
          findResult = new FindResult(result, target, new int[]{rectSub.x, rectSub.y});
        }
        if (SX.isNotNull(findResult)) {
          log.trace("doFind: after down: %%%.2f(?%%%.2f) %d msec",
                  mMinMax.maxVal * 100, target.getWantedScore() * 100, new Date().getTime() - begin_t);
        }
      }
    }
    // ************************************** search in original
    if (((int) (100 * downSizeScore)) == 0 ) {
      begin_t = new Date().getTime();
      result = doFindMatch(target, base, null);
      mMinMax = Core.minMaxLoc(result);
      if (!isCheckLastSeen) {
        log.trace("doFind: search in original: %d msec", new Date().getTime() - begin_t);
      }
      if (mMinMax.maxVal > target.getWantedScore()) {
        findResult = new FindResult(result, target);
      }
    }
    log.trace("doFind: end");
    return findResult;
  }

  private Mat doFindMatch(Element target, Mat base, Mat probe) {
    if (SX.isNull(probe)) {
      probe = target.getContent();
    }
    Mat result = new Mat();
    Mat plainBase = base;
    Mat plainProbe = probe;
    if (!target.isPlainColor()) {
      Imgproc.matchTemplate(base, probe, result, Imgproc.TM_CCOEFF_NORMED);
    } else {
      if (target.isBlack()) {
        Core.bitwise_not(base, plainBase);
        Core.bitwise_not(probe, plainProbe);
      }
      Imgproc.matchTemplate(plainBase, plainProbe, result, Imgproc.TM_SQDIFF_NORMED);
      Core.subtract(Mat.ones(result.size(), CvType.CV_32F), result, result);
    }
    return result;
  }

  private static class FindResult implements Iterator<Element> {

    private static final SXLog log = SX.getLogger("SX.FindResult");

    private FindResult() {
    }

    public FindResult(Mat result, Element target) {
      this.result = result;
      this.target = target;
    }

    public FindResult(Mat result, Element target, int[] off) {
      this(result, target);
      offX = off[0];
      offY = off[1];
    }

    public String name = "";
    //  public boolean success = false;

    private Element target = null;
    private Mat result = null;
    private Core.MinMaxLocResult resultMinMax = null;
    private int offX = 0;
    private int offY = 0;

    private double currentScore = -1;
    double firstScore = -1;
    double scoreMaxDiff = 0.005;

    private int currentX = -1;
    private int currentY = -1;

    public boolean hasNext() {
      resultMinMax = Core.minMaxLoc(result);
      currentScore = resultMinMax.maxVal;
      currentX = (int) resultMinMax.maxLoc.x;
      currentY = (int) resultMinMax.maxLoc.y;
      if (firstScore < 0) {
        firstScore = currentScore;
      }
      if (currentScore > target.getScore() && currentScore > firstScore - scoreMaxDiff) {
        return true;
      }
      return false;
    }

    public Element next() {
      Element match = null;
      if (hasNext()) {
        match = new Element(new Element(currentX + offX , currentY + offY, target.w, target.h), currentScore);
        int margin = getPurgeMargin();
        Range rangeX = new Range(Math.max(currentX - margin, 0), currentX + 1);
        Range rangeY = new Range(Math.max(currentY - margin, 0), currentY +1);
        result.colRange(rangeX).rowRange(rangeY).setTo(new Scalar(0f));
      }
      return match;
    }

    private int getPurgeMargin() {
      if (currentScore < 0.95) {
        return 4;
      } else if (currentScore < 0.85) {
        return 8;
      } else if (currentScore < 0.71) {
        return 16;
      }
      return 2;
    }

    double bestScore = 0;
    double meanScore = 0;
    double stdDevScore = 0;

    public List<Element> getMatches() {
      if (hasNext()) {
        List<Element> matches = new ArrayList<Element>();
        List<Double> scores = new ArrayList<>();
        while (hasNext()) {
          Element match = next();
          meanScore = (meanScore * matches.size() + match.getScore()) / (matches.size() + 1);
          bestScore = Math.max(bestScore, match.getScore());
          matches.add(match);
          scores.add(match.getScore());
        }
        stdDevScore = calcStdDev(scores, meanScore);
        return matches;
      }
      return null;
    }

    public double[] getScores() {
      return new double[] {bestScore, meanScore, stdDevScore};
    }

    private double calcStdDev(List<Double> doubles, double mean) {
      double stdDev = 0;
      for (double doubleVal : doubles) {
        stdDev += (doubleVal - mean) * (doubleVal - mean);
      }
      return Math.sqrt(stdDev / doubles.size());
    }

    @Override
    public void remove() {
    }
  }
  //</editor-fold>

  //<editor-fold desc="find extended">
  public Element findBest(List<Element> targets) {
    List<Element> mList = findAny(targets);
    if (mList != null) {
      if (mList.size() > 1) {
        Collections.sort(mList, new Comparator<Element>() {
          @Override
          public int compare(Element m1, Element m2) {
            double ms = m2.getScore() - m1.getScore();
            if (ms < 0) {
              return -1;
            } else if (ms > 0) {
              return 1;
            }
            return 0;
          }
        });
      }
      return mList.get(0);
    }
    return null;
  }

  public List<Element> findAny(List<Element> targets) {
    List<Element> mList = findAnyCollect(targets);
    return mList;
  }

  private List<Element> findAnyCollect(List<Element> targets) {
    int targetCount = 0;
    if (SX.isNull(targets)) {
      return null;
    } else {
      targetCount = targets.size();
    }
    List<Element> matches = new ArrayList<>();
    SubFindRun[] theSubs = new SubFindRun[targetCount];
    int nobj = 0;
    for (Element target : targets) {
      matches.add(null);
      theSubs[nobj] = null;
      if (target != null) {
        theSubs[nobj] = new SubFindRun(matches, nobj, target);
        new Thread(theSubs[nobj]).start();
      }
      nobj++;
    }
    log.trace("findAnyCollect: waiting for SubFindRuns");
    nobj = 0;
    boolean all = false;
    int waitCount = targetCount;
    while (!all) {
      all = true;
      for (SubFindRun sub : theSubs) {
        if (sub.hasFinished()) {
          waitCount -= 1;
        }
        all &= sub.hasFinished();
      }
      SX.pause((waitCount * 10) / 1000);
    }
    log.trace("findAnyCollect: SubFindRuns finished");
    nobj = 0;
    for (Element match : matches) {
      if (match != null) {
        match.setMatchIndex(nobj);
      }
      nobj++;
    }
    return matches;
  }

  private class SubFindRun implements Runnable {

    List<Element> matches;
    boolean finished = false;
    int subN;
    Element target;

    public SubFindRun(List<Element> matches, int pSubN, Element target) {
      subN = pSubN;
      this.matches = matches;
      this.target = target;
    }

    @Override
    public void run() {
      try {
        Element match = find(target);
        matches.set(subN, null);
        if (SX.isNotNull(match)) {
          matches.set(subN, match);
        }
      } catch (Exception ex) {
        log.error("findAnyCollect: image file not found:\n", target);
      }
      hasFinished(true);
    }

    public boolean hasFinished() {
      return hasFinished(false);
    }

    public synchronized boolean hasFinished(boolean state) {
      if (state) {
        finished = true;
      }
      return finished;
    }
  }
  //</editor-fold>

  //<editor-fold desc="detect changes">
  public boolean hasChanges(Mat base, Mat current) {
    int PIXEL_DIFF_THRESHOLD = 5;
    int IMAGE_DIFF_THRESHOLD = 5;
    Mat bg = new Mat();
    Mat cg = new Mat();
    Mat diff = new Mat();
    Mat tdiff = new Mat();

    Imgproc.cvtColor(base, bg, Imgproc.COLOR_BGR2GRAY);
    Imgproc.cvtColor(current, cg, Imgproc.COLOR_BGR2GRAY);
    Core.absdiff(bg, cg, diff);
    Imgproc.threshold(diff, tdiff, PIXEL_DIFF_THRESHOLD, 0.0, Imgproc.THRESH_TOZERO);
    if (Core.countNonZero(tdiff) <= IMAGE_DIFF_THRESHOLD) {
      return false;
    }

    Imgproc.threshold(diff, diff, PIXEL_DIFF_THRESHOLD, 255, Imgproc.THRESH_BINARY);
    Imgproc.dilate(diff, diff, new Mat());
    Mat se = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
    Imgproc.morphologyEx(diff, diff, Imgproc.MORPH_CLOSE, se);

    List<MatOfPoint> points = new ArrayList<MatOfPoint>();
    Mat contours = new Mat();
    Imgproc.findContours(diff, points, contours, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
    int n = 0;
    for (Mat pm : points) {
      log.trace("(%d) %s", n++, pm);
      printMatI(pm);
    }
    log.trace("contours: %s", contours);
    printMatI(contours);
    return true;
  }

  //<editor-fold desc="original C++">
/*
  ChangeFinder::find(Mat new_screen_image){

    BaseFinder::find(); // set ROI

    Mat im1 = roiSource;
    Mat im2 = Mat(new_screen_image,roi);

    Mat gray1;
    Mat gray2;

    // convert image from RGB to grayscale
    cvtColor(im1, gray1, CV_RGB2GRAY);
    cvtColor(im2, gray2, CV_RGB2GRAY);

    Mat diff1;
    absdiff(gray1,gray2,diff1);

    Size size = diff1.size();
    int ch = diff1.channels();
    typedef unsigned char T;

    int diff_cnt = 0;
    for( int i = 0; i < size.height; i++ )
    {
        const T* ptr1 = diff1.ptr<T>(i);
      for( int j = 0; j < size.width; j += ch )
      {
        if (ptr1[j] > PIXEL_DIFF_THRESHOLD)
          diff_cnt++;
      }
    }

    // quickly check if two images are nearly identical
    if (diff_cnt < IMAGE_DIFF_THRESHOLD){
      is_identical = true;
      return;
    }

    threshold(diff1,diff1,PIXEL_DIFF_THRESHOLD,255,CV_THRESH_BINARY);
    dilate(diff1,diff1,Mat());

    // close operation
    Mat se = getStructuringElement(MORPH_ELLIPSE, Size(5,5));
    morphologyEx(diff1, diff1, MORPH_CLOSE, se);

    vector< vector<Point> > contours;
    vector< Vec4i> hierarchy;
    //findContours(diff1, contours, CV_RETR_EXTERNAL, CV_CHAIN_APPROX_NONE, Point());

    storage = cvCreateMemStorage();
    CvSeq* first_contour = NULL;

    CvMat mat = (CvMat) diff1;

    cvFindContours(
            &mat,
            storage,
                     &first_contour,
            sizeof(CvContour),
            CV_RETR_EXTERNAL);

    c = first_contour;
  }

  bool
  ChangeFinder::hasNext(){
    return !is_identical  && c !=NULL;
  }

  FindResult
  ChangeFinder::next(){

    // find bounding boxes
    int x1=source.cols;
    int x2=0;
    int y1=source.rows;
    int y2=0;

    for( int i=0; i < c->total; ++i ){
      CvPoint* p = CV_GET_SEQ_ELEM( CvPoint, c, i );
      if (p->x > x2)
        x2 = p->x;
      if (p->x < x1)
        x1 = p->x;
      if (p->y > y2)
        y2 = p->y;
      if (p->y < y1)
        y1 = p->y;
    }

    FindResult m;
    m.x = x1 + roi.x;
    m.y = y1 + roi.y;
    m.w = x2 - x1 + 1;
    m.h = y2 - y1 + 1;

    c = c->h_next;
    return m;
  }

*/
  //</editor-fold>

  private static void printMatI(Mat mat) {
    int[] data = new int[mat.channels()];
    for (int r = 0; r < mat.rows(); r++) {
      for (int c = 0; c < mat.cols(); c++) {
        mat.get(r, c, data);
        log.trace("(%d, %d) %s", r, c, Arrays.toString(data));
      }
    }
  }

  public void setMinChanges(int min) {
    log.terminate(1, "setMinChanges");
  }
  //</editor-fold>

  public static class PossibleMatch {
    Element what = null;
    public Element getWhat() {
      return what;
    }
    Element where = null;
    public Element getWhere() {
      return where;
    }
    int waitTime = -1;
    Element target = new Element();

    String type = "";
    // ALL for findAll
    // OBSERVE for observe
    // empty for everything else

    Finder finder = null;
    long startTime = new Date().getTime();
    long endTime = startTime;
    long lastRepeatTime = 0;
    long repeatPause = (long) (1000 / SX.getOptionNumber("Settings.WaitScanRate", 3));

    public double getScanRate() {
      return scanRate;
    }

    public void setScanRate() {
      setScanRate(-1);
    }

    public void setScanRate(double scanRate) {
      this.scanRate = scanRate;
      if (scanRate < 0) {
        if ("OBSERVE".equals(type)) {
          repeatPause = (long) (1000 / SX.getOptionNumber("Settings.ObserveScanRate", 3));
        } else
          repeatPause = (long) (1000 / SX.getOptionNumber("Settings.WaitScanRate", 3));
      }
    }

    double scanRate = -1;

    boolean imageMissingWhere = false;

    public boolean isImageMissingWhere() {
      return imageMissingWhere;
    }

    boolean imageMissingWhat = false;

    public boolean isImageMissingWhat() {
      return imageMissingWhat;
    }

    public boolean isValid() {
      return valid;
    }

    boolean valid = false;

    public PossibleMatch() {
      init("");
    }

    public PossibleMatch(String type) {
      init(type);
    }

    private void init(String type) {
      this.type = type;
      setScanRate();
    }

    public Element get(Object... args) {
      String form = "EvaluateTarget: ";
      for (Object arg : args) {
        form += "%s, ";
      }
      log.trace(form, args);
      Object args0, args1;
      if (args.length > 0) {
        args0 = args[0];
        if (args0 instanceof String) {
          what = new Picture((String) args0);
          if (!what.isValid()) {
            imageMissingWhat = true;
          }
        } else if (args0 instanceof Element) {
          what = (Element) args0;
        } else {
          log.error("EvaluateTarget: args0 invalid: %s", args0);
          what = new Element();
        }
        if (SX.isNotNull(what) && args.length == 2 && !imageMissingWhat) {
          args1 = args[1];
          if (args1 instanceof String) {
            where = new Picture((String) args1);
            if (!where.isValid()) {
              imageMissingWhere = true;
            }
          } else if (args1 instanceof Element) {
            where = (Element) args1;
          } else if (args1 instanceof Double) {
            waitTime = (int) (1000 * (Double) args1);
          } else if (args1 instanceof Integer) {
            waitTime = 1000 * (Integer) args1;
          } else {
            log.error("EvaluateTarget: args1 invalid: %s", args0);
          }
        }
        if (SX.isNotNull(what) && !imageMissingWhat && !imageMissingWhere) {
          if (what.isTarget()) {
            if (SX.isNull(where)) {
              where = Do.on();
            }
            if (where.isOnScreen()) {
              where.capture();
            }
            finder = new Finder(where);
            where.setLastTarget(null);
            if (finder.isValid()) {
              where.setLastTarget(what);
              target = where;
              if (waitTime < 0) {
                waitTime = (int) (1000 * Math.max(where.getWaitForMatch(), what.getWaitForThis()));
              }
              endTime = startTime + waitTime;
              if (type.isEmpty()) {
                lastRepeatTime = new Date().getTime();
                finder.find(what);
              } else if (type == "ALL") {
                finder.findAll(what);
              }
            }
          } else {
            target = what;
          }
        }
      }
      return target;
    }

    public void repeat() {
      long now = new Date().getTime();
      long repeatDelay = lastRepeatTime + repeatPause - now;
      if (repeatDelay > 0) {
        try {
          Thread.sleep(repeatDelay);
        } catch (InterruptedException ex) {
        }
      }
      log.trace("EvaluateTarget: repeat: delayed: %d", repeatDelay);
      lastRepeatTime = new Date().getTime();
      if (new Date().getTime() < endTime) {
        if (where.isOnScreen()) {
          where.capture();
          finder.refreshBase();
        }
        if (type.isEmpty()) {
          finder.find(what);
        } else if (type == "ALL") {
          finder.findAll(what);
        }
        if (where.hasMatch() || where.hasMatches()) {
          long waitedFor = new Date().getTime() - startTime;
          what.setLastWaitForThis(waitedFor);
          where.setLastWaitForMatch(waitedFor);
        }
      }
    }

    public boolean shouldWait() {
      if (waitTime < 0) {
        return false;
      }
      return new Date().getTime() < endTime;
    }

    @Override
    public String toString() {
      return String.format("what: %s, where: %s: wait: %d sec", what, where, waitTime);
    }
  }
}
