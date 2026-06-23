package com.fastclaim.entity;

import jakarta.persistence.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Entity
@Table(name = "vehicle")
public class Vehicle {

    private static final Logger log = LoggerFactory.getLogger(Vehicle.class);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String licensePlate;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private String brand;

    @Column(name = "vehicle_year", nullable = false)
    private int year;

    @Column(name = "vehicle_value", nullable = false)
    private double vehicleValue;

    @ManyToOne
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    public Vehicle() {}

    // Sentinel 占位对象 — 与 Customer.lookupFailed() 同理，为 UTILITY 规划器提供类型化占位
    public static Vehicle lookupFailed() {
        log.warn("车辆查找失败，创建 Sentinel 占位对象");
        Vehicle v = new Vehicle();
        v.licensePlate = "__sentinel__";
        return v;
    }

    public static boolean isLookupFailed(Vehicle v) {
        return "__sentinel__".equals(v.getLicensePlate());
    }

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getLicensePlate() { return licensePlate; }
    public void setLicensePlate(String licensePlate) { this.licensePlate = licensePlate; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public int getYear() { return year; }
    public void setYear(int year) { this.year = year; }

    public double getVehicleValue() { return vehicleValue; }
    public void setVehicleValue(double vehicleValue) { this.vehicleValue = vehicleValue; }

    public Customer getCustomer() { return customer; }
    public void setCustomer(Customer customer) { this.customer = customer; }
}
