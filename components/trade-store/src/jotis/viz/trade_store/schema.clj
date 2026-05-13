(ns jotis.viz.trade-store.schema)

;; 3NF relational schema for the 'trader' schema in the 'traderdb' Postgres database.
;; Executed in order during init!

(def ddl-stmts
  ["CREATE SCHEMA IF NOT EXISTS trader"

   "CREATE TABLE IF NOT EXISTS trader.trades (
      id               UUID          PRIMARY KEY,
      ticker           VARCHAR(20)   NOT NULL,
      instrument_type  VARCHAR(30)   NOT NULL,
      option_type      VARCHAR(10),
      status           VARCHAR(20)   NOT NULL DEFAULT 'open',
      qty              INTEGER       NOT NULL,
      open_value       NUMERIC(14,6) NOT NULL,
      close_value      NUMERIC(14,6),
      pnl              NUMERIC(14,6),
      account_pct      NUMERIC(8,4)  NOT NULL,
      alpaca_order_id  VARCHAR(100)  NOT NULL,
      notes            TEXT,
      opened_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
      closed_at        TIMESTAMPTZ
    )"

   "CREATE TABLE IF NOT EXISTS trader.legs (
      id           SERIAL        PRIMARY KEY,
      trade_id     UUID          NOT NULL REFERENCES trader.trades(id) ON DELETE CASCADE,
      symbol       VARCHAR(50)   NOT NULL,
      option_type  VARCHAR(10)   NOT NULL,
      side         VARCHAR(10)   NOT NULL,
      strike       NUMERIC(12,4),
      expiry       DATE,
      open_price   NUMERIC(14,6) NOT NULL DEFAULT 0,
      close_price  NUMERIC(14,6)
    )"])
