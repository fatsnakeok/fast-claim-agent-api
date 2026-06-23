package com.fastclaim.config;

import com.fastclaim.entity.Customer;
import com.fastclaim.entity.Vehicle;
import com.fastclaim.repository.CustomerRepository;
import com.fastclaim.repository.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final CustomerRepository customerRepository;
    private final VehicleRepository vehicleRepository;

    public DataInitializer(CustomerRepository customerRepository, VehicleRepository vehicleRepository) {
        this.customerRepository = customerRepository;
        this.vehicleRepository = vehicleRepository;
    }

    @Override
    public void run(String... args) {
        // 幂等保护：仅在空库时初始化，避免重复插入种子数据
        if (customerRepository.count() > 0) {
            log.info("Seed data already exists, skipping initialization");
            return;
        }

        log.info("Initializing seed data...");

        // 低风险用户 — 预期核保结果 APPROVED，覆盖批准路径
        Customer alice = createCustomer("low-risk-user", "Alice Wang", LocalDate.of(1985, 3, 15), 15, 1, "alice@example.com", "13800001111");
        customerRepository.save(alice);
        createVehicle(alice, "LOW001", "Toyota RAV4", "Toyota", 2022, 300000);

        // 中风险用户 — 预期核保结果 REFERRED，覆盖转人工路径
        Customer bob = createCustomer("medium-risk-user", "Bob Chen", LocalDate.of(1999, 7, 20), 4, 2, "bob@example.com", "13800002222");
        customerRepository.save(bob);
        createVehicle(bob, "MED001", "Honda Civic", "Honda", 2018, 180000);

        // 高风险用户 — 预期核保结果 DECLINED，覆盖拒绝路径
        Customer charlie = createCustomer("high-risk-user", "Charlie Zhang", LocalDate.of(2005, 1, 10), 1, 3, "charlie@example.com", "13800003333");
        customerRepository.save(charlie);
        createVehicle(charlie, "HIGH001", "BMW X5", "BMW", 2013, 600000);

        // 遗留用户 — 向后兼容，无预设核保结果
        Customer john = createCustomer("user", "John Doe", LocalDate.of(1985, 5, 15), 15, 2, "john@example.com", "13800004444");
        customerRepository.save(john);
        createVehicle(john, "ABC123", "Toyota RAV4", "Toyota", 2020, 250000);

        // 遗留管理员 — 向后兼容，无预设核保结果
        Customer jane = createCustomer("admin", "Jane Smith", LocalDate.of(1990, 10, 20), 8, 0, "jane@example.com", "13800005555");
        customerRepository.save(jane);
        createVehicle(jane, "XYZ789", "Tesla Model 3", "Tesla", 2022, 450000);

        log.info("Seed data initialization complete: {} customers, {} vehicles",
                customerRepository.count(), vehicleRepository.count());
    }

    private Customer createCustomer(String userId, String name, LocalDate dob, int drivingYears, int accidents, String email, String phone) {
        Customer c = new Customer();
        c.setUserId(userId);
        c.setName(name);
        c.setDateOfBirth(dob);
        c.setDrivingExperienceYears(drivingYears);
        c.setAccidentCount(accidents);
        c.setEmail(email);
        c.setPhone(phone);
        return c;
    }

    private void createVehicle(Customer customer, String plate, String model, String brand, int year, double value) {
        Vehicle v = new Vehicle();
        v.setLicensePlate(plate);
        v.setModel(model);
        v.setBrand(brand);
        v.setYear(year);
        v.setVehicleValue(value);
        v.setCustomer(customer);
        vehicleRepository.save(v);
    }
}
