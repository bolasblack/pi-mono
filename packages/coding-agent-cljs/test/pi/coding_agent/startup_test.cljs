(ns pi.coding-agent.startup-test
  (:require [cljs.test :refer [deftest is testing]]
            [pi.coding-agent.startup :as startup]))

(deftest tmux-keyboard-warning-test
  (testing "returns nil when extended-keys is 'on' and format is not xterm"
    (is (nil? (startup/tmux-keyboard-warning "on" "csi-u"))))

  (testing "returns nil when extended-keys is 'always' and format is not xterm"
    (is (nil? (startup/tmux-keyboard-warning "always" "csi-u"))))

  (testing "returns nil when extended-keys is 'on' and format is nil"
    (is (nil? (startup/tmux-keyboard-warning "on" nil))))

  (testing "warns when extended-keys is off"
    (let [warning (startup/tmux-keyboard-warning "off" "csi-u")]
      (is (some? warning))
      (is (re-find #"extended-keys is off" warning))))

  (testing "warns when extended-keys is missing/nil"
    (let [warning (startup/tmux-keyboard-warning nil "csi-u")]
      (is (some? warning))
      (is (re-find #"extended-keys is off" warning))))

  (testing "warns when extended-keys-format is xterm even if keys are on"
    (let [warning (startup/tmux-keyboard-warning "on" "xterm")]
      (is (some? warning))
      (is (re-find #"extended-keys-format is xterm" warning)))))

(deftest changelog-display-action-test
  (testing "skips for resumed sessions (message-count > 0)"
    (is (= :skip (startup/changelog-display-action 5 "1.0.0"))))

  (testing "records version on fresh install (no last-version)"
    (is (= :record-version (startup/changelog-display-action 0 nil))))

  (testing "returns :check-entries when messages empty and last-version exists"
    (is (= :check-entries (startup/changelog-display-action 0 "1.0.0")))))
