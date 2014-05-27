(ns client.core
  (:require-macros
    [cljs.core.async.macros :refer [go alt!]])
  (:require
    [client.board :refer [piece-fits?
                          rotate-piece
                          start-position
                          empty-board
                          get-drop-pos
                          get-rand-piece
                          get-rand-diff-piece
                          write-piece-to-board
                          write-piece-behind-board
                          create-drawable-board
                          get-filled-row-indices
                          clear-rows
                          collapse-rows
                          highlight-rows
                          write-to-board
                          n-rows
                          n-cols]]
    [client.rules :refer [get-points
                          level-up?
                          get-level-speed]]
    [client.paint :refer [size-canvas!
                          draw-board!]]
    [client.repl :as repl]
    [client.socket :refer [socket connect-socket!]]
    [client.vcr :refer [vcr toggle-record! record-frame!]]
    [cljs.core.async :refer [put! chan <! timeout]]))

(enable-console-print!)

;;------------------------------------------------------------
;; STATE OF THE GAME
;;------------------------------------------------------------

(def state (atom {:next-piece nil
                  :piece nil
                  :position nil
                  :board empty-board

                  :score 0
                  :level 0
                  :level-lines 0
                  :total-lines 0}))

; required for pausing/resuming the gravity routine
(def pause-grav (chan))
(def resume-grav (chan))

;;------------------------------------------------------------
;; STATE MONITOR
;;------------------------------------------------------------

(defn drawable-board
  "Draw the current state of the board."
  []
  (let [{piece :piece
         [x y] :position
         board :board} @state]
    (create-drawable-board piece x y board)))

(defn make-redraw-chan
  "Create a channel that receives a value everytime a redraw is requested."
  []
  (let [redraw-chan (chan)
        request-anim #(.requestAnimationFrame js/window %)]
    (letfn [(trigger-redraw []
              (put! redraw-chan 1)
              (request-anim trigger-redraw))]
      (request-anim trigger-redraw)
      redraw-chan)))

(defn go-go-draw!
  "Kicks off the drawing routine."
  []
  (let [redraw-chan (make-redraw-chan)]
    (go
      (loop [board nil]
        (<! redraw-chan)
        (let [new-board (drawable-board)]
          (when (not= board new-board)
            (draw-board! new-board)
            (if (:recording @vcr)
              (record-frame!)))
          (recur new-board))))))

;;------------------------------------------------------------
;; Game-driven STATE CHANGES
;;------------------------------------------------------------

(defn go-go-game-over!
  "Kicks off game over routine. (and get to the chopper)"
  []
  (go
    (doseq [y (reverse (range n-rows))
            x (range n-cols)]
      (if (even? x)
        (<! (timeout 2)))
      (swap! state update-in [:board] #(write-to-board x y :I %)))))

(defn spawn-piece! 
  "Spawns the given piece at the starting position."
  [piece]
    (swap! state assoc :piece piece
                       :position start-position)
    (put! resume-grav 0))

(defn try-spawn-piece!
  "Checks if new piece can be written to starting position."
  []
  (let [piece (or (:next-piece @state) (get-rand-piece))
        [x y] start-position
        board (:board @state)]
    (swap! state assoc :next-piece (get-rand-diff-piece piece))
    (if (piece-fits? piece x y board)
      (spawn-piece! piece)
      (go
        ; Show piece that we attempted to spawn, drawn behind the other pieces.
        ; Then pause before kicking off gameover animation.
        (swap! state update-in [:board] #(write-piece-behind-board piece x y %))
        (<! (timeout (get-level-speed (:level @state))))
        (go-go-game-over!)))))

(defn update-points!
  [rows-cleared]
  (let [n rows-cleared
        level (:level @state)
        points (get-points n (inc level))
        level-lines (+ n (:level-lines @state))]

    ; update the score before a possible level-up
    (swap! state update-in [:score] + points)

    (if (level-up? level-lines)
      (do
        (swap! state update-in [:level] inc)
        (swap! state assoc :level-lines 0)
        (js/console.log "leveled up"))
      (swap! state assoc :level-lines level-lines))

    (swap! state update-in [:total-lines] + n)

    (js/console.log "level-lines:" (:level-lines @state))
    (js/console.log "total-lines:" (:total-lines @state))
    (js/console.log "level:" (:level @state))

    (js/console.log "Points scored: ")
    (js/console.log points)
    (js/console.log "Current Score: ")
    (js/console.log (:score @state))))

(defn collapse-rows!
  "Collapse the given row indices."
  [rows]
  (let [n (count rows)
        board (collapse-rows rows (:board @state))]
    (swap! state assoc :board board)
    (update-points! n)))

(defn go-go-collapse!
  "Starts the collapse animation if we need to, returning nil or the animation channel."
  []
  (let [board (:board @state)
        rows (get-filled-row-indices board)
        flashed-board (highlight-rows rows board)
        cleared-board (clear-rows rows board)]

    (when-not (zero? (count rows))
      (go
        ; blink n times
        (doseq [i (range 3)]
          (swap! state assoc :board flashed-board)
          (<! (timeout 170))
          (swap! state assoc :board board)
          (<! (timeout 170)))

        ; clear rows to create a gap, and pause
        (swap! state assoc :board cleared-board)
        (<! (timeout 220))

        ; finally collapse
        (collapse-rows! rows)))))

(defn lock-piece!
  "Lock the current piece into the board."
  []
  (let [[x y] (:position @state)
        piece (:piece @state)
        board (:board @state)]
    (swap! state assoc  :board (write-piece-to-board piece x y board)
                        :piece nil)
    (put! pause-grav 0)

    ; If collapse routine returns a channel...
    ; then wait for it before spawning a new piece.
    (if-let [collapse-anim (go-go-collapse!)]
      (go
        (<! collapse-anim)
        (<! (timeout 100))
        (try-spawn-piece!))
      (try-spawn-piece!))))

(defn try-gravity!
  "Move current piece down 1 if possible, else lock the piece."
  []
  (let [piece (:piece @state)
        [x y] (:position @state)
        board (:board @state)
        ny (inc y)]
    (if (piece-fits? piece x ny board)
      (swap! state assoc-in [:position 1] ny)
      (lock-piece!))))

(defn go-go-gravity!
  "Starts the gravity routine."
  []
  ; Make sure gravity starts in paused mode.
  ; Spawning the piece will signal the first "resume".
  (put! pause-grav 0)

  (go
    (loop []
      (let [cs [(timeout (get-level-speed (:level @state))) 
                pause-grav]                 ; channels to listen to (timeout, pause)
            [_ c] (alts! cs)]               ; get the first channel to receive a value
        (if (= pause-grav c)                ; if "pause" received, wait for "resume"
          (<! resume-grav)
          (try-gravity!))
        (recur)))))

;;------------------------------------------------------------
;; Input-driven STATE CHANGES
;;------------------------------------------------------------

(def key-codes {:left 37
                :up 38
                :right 39
                :down 40
                :space 32
                :shift 16})

(defn try-move!
  "Try moving the current piece to the given offset."
  [dx dy]
  (let [[x y] (:position @state)
        piece (:piece @state)
        board (:board @state)
        nx (+ dx x)
        ny (+ dy y)]
    (if (piece-fits? piece nx ny board)
      (swap! state assoc :position [nx ny]))))

(defn try-rotate!
  "Try rotating the current piece."
  []
  (let [[x y] (:position @state)
        piece (:piece @state)
        board (:board @state)
        new-piece (rotate-piece piece)]
    (if (piece-fits? new-piece x y board)
      (swap! state assoc :piece new-piece))))

(defn hard-drop!
  "Hard drop the current piece."
  []
  (let [[x y] (:position @state)
        piece (:piece @state)
        board (:board @state)
        ny (get-drop-pos piece x y board)]
    (swap! state assoc :position [x ny])
    (lock-piece!)))

(defn add-key-events
  "Add all the key inputs."
  []
  (.addEventListener js/window "keydown"
     (fn [e]
       (if (:piece @state)
         (let [code (aget e "keyCode")]
          (cond
            (= code (:down key-codes))  (do (try-move!  0  1) (.preventDefault e))
            (= code (:left key-codes))  (do (try-move! -1  0) (.preventDefault e))
            (= code (:right key-codes)) (do (try-move!  1  0) (.preventDefault e))
            (= code (:space key-codes)) (do (hard-drop!)      (.preventDefault e))
            (= code (:up key-codes))    (do (try-rotate!)     (.preventDefault e))))))
        )
  (.addEventListener js/window "keyup"
     (fn [e]
       (let [code (aget e "keyCode")]
         (cond
           (= code (:shift key-codes)) (toggle-record!))))))

;;------------------------------------------------------------
;; Facilities
;;------------------------------------------------------------

(defn auto-refresh
  "Automatically refresh the page whenever a cljs file is compiled."
  []
  (.on @socket "refresh" #(.reload js/location)))

;;------------------------------------------------------------
;; Entry Point
;;------------------------------------------------------------

(defn init []
  (size-canvas!)
  (try-spawn-piece!)
  (add-key-events)
  (go-go-draw!)
  (go-go-gravity!)

  (repl/connect)
  (connect-socket!)
  (auto-refresh)
  )

(.addEventListener js/window "load" init)
