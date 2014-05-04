(ns t3tr0s.core
  (:require
    [clojure.string :refer [join]]))

(enable-console-print!)

(def pieces
  {:I [[0,0],[0,-1],[0,1],[0,2]]
   :L [[0,0],[0,1],[1,1],[0,-1]]
   :J [[0,0],[0,-1],[0,1],[-1,1]]
   :S [[0,0],[-1,0],[0,-1],[1,-1]]
   :Z [[0,0],[-1,-1],[0,-1],[1,0]]
   :O [[0,0],[-1,0],[-1,1],[0,1]]
   :T [[0,0],[-1,0],[1,0],[0,1]]})

(def state (atom {:board [[0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]
                          [0,0,0,0,0,0,0,0,0,0]]}))

(defn get-cell-str
  "Return a space or asterisk for the given cell."
  [cell]
  (if (= 0 cell) "_" "*"))

(defn row-str
  "Create a string from a board row."
  [row]
  (join "" (map get-cell-str row)))

(defn board-str
  "Create a string from the tetris board."
  [board]
  (join "\n" (map row-str board)))

(defn print-board []
  (println (board-str (:board @state))))

(defn init []
  (print-board))

(.addEventListener js/window "load" init)