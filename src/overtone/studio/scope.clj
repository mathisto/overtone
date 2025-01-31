(ns
    ^{:doc "An oscilloscope style waveform viewer"
      :author "Jeff Rose & Sam Aaron"}
    overtone.studio.scope
  (:import [java.awt Graphics2D Dimension Color BorderLayout RenderingHints LayoutManager]
           [java.awt.event WindowListener ComponentListener]
           [javax.swing JFrame JPanel JSlider])
  (:use [clojure.stacktrace]
        [overtone.helpers lib]
        [overtone.libs event deps]
        [overtone.sc.defaults]
        [overtone.sc.server]
        [overtone.sc.ugens]
        [overtone.sc.buffer]
        [overtone.sc.node]
        [overtone.sc.foundation-groups]
        [overtone.sc.bus]
        [overtone.sc.synth]
        [overtone.sc.cgens.buf-io]
        [overtone.studio core util])
  (:require [overtone.config.log :as log]
            [overtone.at-at :as at-at]))

(defonce scope-group*     (ref 0))
(defonce scopes*          (ref {}))
(defonce scope-pool       (at-at/mk-pool))
(defonce scopes-running?* (ref false))

(defonce SCOPE-BUF-SIZE 2048) ; size must be a power of 2 for FFT
(defonce FPS            10)
(defonce WIDTH          600)
(defonce HEIGHT         400)
(defonce X-PADDING      5)
(defonce Y-PADDING      10)

(on-deps :studio-setup-completed ::create-scope-group
  #(dosync
     (ref-set scope-group*
              (group "Scope" :tail (foundation-monitor-group)))
     (satisfy-deps :scope-group-created)))

(defn- ensure-internal-server!
  "Throws an exception if the server isn't internal - scope relies on
  fast access to shared buffers with the server which is currently only
  available with the internal server. Also ensures server is connected."
  []
  (when (server-disconnected?)
    (throw (Exception. "Cannot use scopes until a server has been booted or connected")))
  (when (external-server?)
    (throw (Exception. (str "Sorry, it's only possible to use scopes with an internal server. Your server connection info is as follows: " (connection-info))))))

(defn- update-scope-data
  "Updates the scope by reading the current status of the buffer and repainting."
  [s]

  (let [{:keys [buf _size width height panel y-arrays _x-array]} s
        frames    (if (buffer-live? buf)
                    (buffer-data buf)
                    [])
        step      (/ (:size buf) width)
        y-scale   (- height (* 2 Y-PADDING))
        [y-a y-b] @y-arrays]

    (when-not (empty? frames)
      (dotimes [x width]
        (aset ^ints y-b x
              (int (* y-scale
                      (aget ^floats frames (unchecked-multiply x step))))))
      (reset! y-arrays [y-b y-a])
      (.repaint ^JPanel panel))))

(defn- update-scopes []
  (try
    (dorun (map update-scope-data (vals @scopes*)))
    (catch Exception e
      (println "Exception when updating scopes:" (with-out-str (.printStackTrace e))))))

(defn- paint-scope [^Graphics2D g id]
  (if-let [scope (get @scopes* id)]
    (let [{:keys [background width height color x-array y-arrays slider]} scope
          s-val     (.getValue ^JSlider slider)
          y-zoom    (if (> s-val 49)
                      (+ 1 (* 0.1 (- s-val 50)))
                      (+ (* 0.02 s-val) 0.01))
          y-shift   (+ (/ height 2.0) Y-PADDING)
          [y-a _y-b] @y-arrays]
      (doto g
        (.setRenderingHint RenderingHints/KEY_ANTIALIASING
                           RenderingHints/VALUE_ANTIALIAS_ON)
        (.setColor ^Color background)
        (.fillRect 0 0 width height)
        (.setColor ^Color (Color. 100 100 100))
        (.drawRect 0 0 width height)
        (.setColor ^Color color)
        (.translate (double 0) y-shift)
        (.scale 1 (* -1 y-zoom))
        (.drawPolyline ^ints x-array ^ints y-a width)))))

(defn- scope-panel [id width height]
  (let [panel (proxy [JPanel] [true]
                (paint [g] (paint-scope g id)))
        _ (.setPreferredSize panel (Dimension. width height))]
    panel))

(defn- scope-frame
  "Display scope window. If you specify keep-on-top to be true, the
  window will stay on top of the other windows in your environment."
  ([panel slider title keep-on-top width height]
   (let [f    (new JFrame ^java.lang.String title)
         cp   (.getContentPane f)
         side (new JPanel ^LayoutManager (BorderLayout.))]
     (.add side ^JSlider slider BorderLayout/CENTER)
     (doto cp
       (.add side BorderLayout/WEST)
       (.add ^JPanel panel BorderLayout/CENTER))
     (doto f
       (.setPreferredSize (Dimension. width height))
       (.pack)
       (.show)
       (.setAlwaysOnTop keep-on-top)))))

(defn scopes-start
  "Schedule the scope to be updated every (/ 1000 FPS) ms (unless the
  scopes are already running in which case it does nothing."
  []
  (ensure-internal-server!)
  (dosync
   (when-not @scopes-running?*
     (at-at/every (/ 1000 FPS) #'update-scopes scope-pool :desc "Scope refresh fn")
     (ref-set scopes-running?* true))))

(defn- reset-data-arrays
  [scope]
  (let [width     (scope :width)
        x-array   (scope :x-array)
        height    (scope :height)
        [y-a y-b] @(scope :y-arrays)]

    (dotimes [i width]
      (aset ^ints x-array i i))

    (dotimes [i width]
      (aset ^ints y-a i (long (/ height 2)))
      (aset ^ints y-b i (long (/ height 2))))))

(defn- empty-scope-data
  []
  (dorun (map reset-data-arrays (vals @scopes*))))

(defn scopes-stop
  "Stop all scopes from running."
  []
  (ensure-internal-server!)
  (at-at/stop-and-reset-pool! scope-pool)
  (empty-scope-data)
  (dosync (ref-set scopes-running?* false)))

(defn- start-bus-synth
  [bus buf control-rate?]
  (if control-rate?
    (control-bus->buf [:tail @scope-group*] bus buf)
    (bus->buf [:tail @scope-group*] bus buf)))

(defn- scope-bus
  "Set a bus to view in the scope."
  [s control-rate?]
  (let [buf       (buffer SCOPE-BUF-SIZE)
        bus-synth (start-bus-synth (:thing s) buf control-rate?)]
    (assoc s
      :size SCOPE-BUF-SIZE
      :bus-synth bus-synth
      :buf buf)))

(defsynth bus-freqs->buf
  [in-bus 0 scope-buf 1 fft-buf-size 2048 rate 1 db-factor 0.02]
  (let [phase     (- 1 (* rate (reciprocal fft-buf-size)))
        fft-buf   (local-buf fft-buf-size 1)
        n-samples (* 0.5 (- (buf-samples:ir fft-buf) 2))
        signal    (in in-bus 1)
        freqs     (fft fft-buf signal 0.75 HANN)
        smoothed  (pv-mag-smear fft-buf 1)
        indexer   (+ n-samples 2
                     (* (lf-saw (/ rate (buf-dur:ir fft-buf)) phase)
                        n-samples))
        indexer   (round indexer 2)
        src       (buf-rd 1 fft-buf indexer 1 1)
        freq-vals (+ 1 (* db-factor (ampdb (* src 0.00285))))]
    (record-buf freq-vals scope-buf)))

(defn- start-bus-freq-synth
  [bus buf]
  (bus-freqs->buf [:tail @scope-group*] bus buf))

(defn- scope-bus-freq
  [s]
  (let [buf       (buffer SCOPE-BUF-SIZE)
        bus-synth (start-bus-freq-synth (:thing s) buf)]
    (assoc s
      :size SCOPE-BUF-SIZE
      :bus-synth bus-synth
      :buf buf)))

(defn- scope-buf
  "Set a buffer to view in the scope."
  [s]
  (let [buf (:thing s)]
    (assoc s
      :size (:size buf)
      :buf  buf)))

(defn scope-close
  "Close a given scope. Copes with the case where the server has crashed
  by handling timeout errors when killing the scope's bus-synth."
  [s]
  (log/info (str "Closing scope: \n" s))
  (let [{:keys [id bus-synth _buf]} s]
    (when (and (not= :buf (:kind s))
               (:buf s))
      (buffer-free (:buf s)))
    (when (and bus-synth
               (server-connected?))
      (try
        (kill bus-synth)
        (catch Exception _e)))
    (dosync (alter scopes* dissoc id))))

(defn- mk-scope
  [thing kind keep-on-top width height]
  (let [thing-id (to-sc-id thing)
        scope-id (uuid)
        name     (str kind ": " thing-id " " (if-not (empty? (:name thing))
                                               (str "[" (:name thing) "]")
                                               ""))
        panel    (scope-panel scope-id width height)
        slider   (JSlider. JSlider/VERTICAL 0 99 50)
        frame    (scope-frame panel slider name keep-on-top width height)
        x-array  (int-array width)
        y-a      (int-array width)
        y-b      (int-array width)
        scope    {:id         scope-id
                  :name       name
                  :size       0
                  :thing      thing
                  :panel      panel
                  :slider     slider
                  :kind       kind
                  :color      (Color. 0 130 226)
                  :background (Color. 50 50 50)
                  :frame      frame
                  :width      width
                  :height     height
                  :x-array    x-array
                  :y-arrays   (atom [y-a y-b])}

        _        (reset-data-arrays scope)]
    (.addWindowListener ^JFrame frame
                        (reify WindowListener
                          (windowActivated [_this _e])
                          (windowClosing [_this _e]
                            (scope-close (get @scopes* scope-id)))
                          (windowDeactivated [_this _e])
                          (windowDeiconified [_this _e])
                          (windowIconified [_this _e])
                          (windowOpened [_this _e])
                          (windowClosed [_this _e])))
    (comment .addComponentListener frame
             (reify ComponentListener
               (componentHidden [_this _e])
               (componentMoved  [_this _e])
               (componentResized [_this _e]
                 (let [w (.getWidth frame)
                       h (.getHeight frame)
                       xs (int-array w)
                       ya (int-array w)
                       yb (int-array w)]
                   (dosync
                    (let [s (get (ensure scopes*) scope-id)
                          s (assoc s
                                   :width w
                                   :height h
                                   :x-array xs
                                   :y-arrays (atom [ya yb]))]
                      (alter scopes* assoc scope-id s)))))
               (componentShown [_this _e])))

    (case kind
      :control-bus (scope-bus scope true)
      :bus (scope-bus scope false)
      :audio-bus (scope-bus scope false)
      :bus-freq (scope-bus-freq scope)
      :buf (scope-buf scope))))

(defn scope
  "Create a scope for either a bus or a buffer. Defaults to scoping audio-bus 0.
   Example use:

   (scope a-control-bus)
   (scope a-buffer)
   (scope an-audio-bus)
   (scope :audio-bus 1)
   (scope :control-bus 10)
   (scope :buf 10)"
  ([]        (scope :audio-bus 0))
  ([thing]   (cond
              (audio-bus? thing)   (scope :audio-bus thing)
              (control-bus? thing) (scope :control-bus thing)
              (buffer? thing)      (scope :buf thing)
              :else                (scope :audio-bus thing)))
  ([kind id] (scope kind id false))
  ([kind id keep-on-top?]
     (ensure-internal-server!)
     (let [s  (mk-scope id kind keep-on-top? WIDTH HEIGHT)]
       (dosync (alter scopes* assoc (:id s) s))
       (scopes-start))))

(defn pscope
  "Creates a 'permanent' scope, where the window is always kept
  on top of other OS windows. See scope."
  ([]        (scope :audio-bus 0 true))
  ([thing]   (cond
              (audio-bus? thing)   (scope :audio-bus thing true)
              (control-bus? thing) (scope :control-bus thing true)
              (buffer? thing)      (scope :buf thing true)
              :else                (scope :audio-bus thing true)))
  ([kind id] (scope kind id true)))

(defn spectrogram
  "Create frequency scope for a bus.  Defaults to bus 0.
   Example use:
   (spectrogram :bus 1)"
  ([&{:keys [bus keep-on-top]
      :or {bus 0
           keep-on-top false}}]
     (ensure-internal-server!)
     (let [s (mk-scope bus :bus-freq keep-on-top WIDTH HEIGHT)]
       (dosync (alter scopes* assoc (:id s) s))
       (scopes-start))))

(defn- reset-scopes
  "Restart scopes if they have already been running"
  []
  (ensure-internal-server!)
  (dosync
   (ref-set scopes*
            (reduce (fn [new-scopes [k v]]
                      (let [new-scope (if (= :bus (:kind v))
                                        (scope-bus v false)
                                        v)]
                        (assoc new-scopes k new-scope)))
                    {}
                    @scopes*))
   (scopes-start)))


(on-deps #{:synthdefs-loaded :scope-group-created} ::reset-scopes #(when (internal-server?)
                                                                     (reset-scopes)))
(on-sync-event :shutdown (fn [event-info]
                           (when (internal-server?)
                             (scopes-stop)
                             (dorun
                              (map (fn [s] scope-close s) @scopes*))))
               ::stop-scopes)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Spectragraph stuff to be worked on
;; Note: The fft ugen writes into a buffer:
;; dc, nyquist, real, imaginary, real, imaginary....
;  (comment defn- update-scope []
;           (let [{:keys [buf width height panel]} @scope*
;                 frames  (buffer-data buf)
;                 n-reals (/ (- (:size buf) 2) 2)
;                 step    (int (/ n-reals width))
;                 y-scale (/ (- height (* 2 Y-PADDING)) 2)
;                 y-shift (+ (/ height 2) Y-PADDING)]
;             (dotimes [x width]
;               (aset ^ints y-array x
;                     (int (+ y-shift
;                             (* y-scale
;                                (aget ^floats frames
;                                      (+ 2 (* 2 (unchecked-multiply x step))))))))))
;           (.repaint (:panel @scope*)))
;
;  (defsynth freq-scope-zero [in-bus 0 fft-buf 0 scope-buf 1
;                             rate 4 phase 1 db-factor 0.02]
;    (let [n-samples (* 0.5 (- (buf-samples:kr fft-buf) 2))
;          signal (in in-bus)
;          freqs  (fft fft-buf signal 0.75 :hann)
;                                        ;        chain  (pv-mag-smear fft-buf 1)
;          phasor (+ (+ n-samples 2)
;                    (* n-samples
;                       (lf-saw (/ rate (buf-dur:kr fft-buf)) phase)))
;          phasor (round phasor 2)]
;      (scope-out (* db-factor (ampdb (* 0.00285 (buf-rd 1 fft-buf phasor 1 1))))
;                 scope-buf)))
;
;        SynthDef("freqScope0_shm", { arg in=0, fftBufSize = 2048, scopebufnum=1, rate=4, dbFactor = 0.02;
;            var phase = 1 - (rate * fftBufSize.reciprocal);
;            var signal, chain, result, phasor, numSamples, mul, add;
;            var fftbufnum = LocalBuf(fftBufSize, 1);
;            mul = 0.00285;
;            numSamples = (BufSamples.ir(fftbufnum) - 2) * 0.5; // 1023 (bufsize=2048)
;            signal = In.ar(in);
;            chain = FFT(fftbufnum, signal, hop: 0.75, wintype:1);
;            chain = PV_MagSmear(chain, 1);
;            // -1023 to 1023, 0 to 2046, 2 to 2048 (skip first 2 elements DC and Nyquist)
;            phasor = LFSaw.ar(rate/BufDur.ir(fftbufnum), phase, numSamples, numSamples + 2);
;            phasor = phasor.round(2); // the evens are magnitude
;            ScopeOut2.ar( ((BufRd.ar(1, fftbufnum, phasor, 1, 1) * mul).ampdb * dbFactor) + 1, scopebufnum, fftBufSize/rate);
;        }
;
