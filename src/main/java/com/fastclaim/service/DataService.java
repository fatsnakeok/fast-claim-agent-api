package com.fastclaim.service;

import com.fastclaim.dto.VehicleInfo;
import com.fastclaim.entity.Customer;
import com.fastclaim.entity.Vehicle;
import com.fastclaim.repository.CustomerRepository;
import com.fastclaim.repository.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DataService {

    private static final Logger log = LoggerFactory.getLogger(DataService.class);

    private final CustomerRepository customerRepository;
    private final VehicleRepository vehicleRepository;

    public DataService(CustomerRepository customerRepository, VehicleRepository vehicleRepository) {
        this.customerRepository = customerRepository;
        this.vehicleRepository = vehicleRepository;
    }

    /**
     * 按 userId 查找客户，未找到返回 sentinel 而非 null
     */
    public Customer findCustomer(String userId) {
        return customerRepository.findByUserId(userId)
                .orElseGet(() -> {
                    log.warn("客户未找到 — userId: {}", userId);
                    return Customer.lookupFailed();
                });
    }

    /**
     * 按车辆信息 + 客户查找车辆，三级优先级：
     * 1. 车牌精确匹配（需归属当前客户）
     * 2. 品牌 + 型号匹配（客户名下）
     * 3. 客户名下唯一车辆
     */
    public Vehicle findVehicle(VehicleInfo info, Customer customer) {
        if (info.licensePlate() != null && !info.licensePlate().isEmpty()) {
            var optVehicle = vehicleRepository.findByLicensePlate(info.licensePlate());
            if (optVehicle.isPresent()) {
                Vehicle v = optVehicle.get();
                if (v.getCustomer().getId().equals(customer.getId())) {
                    log.debug("车辆查找 — 车牌匹配: {}", info.licensePlate());
                    return v;
                }
                log.warn("车辆查找 — 车牌 {} 存在但不属于客户 {}", info.licensePlate(), customer.getUserId());
            }
        }

        List<Vehicle> customerVehicles = vehicleRepository.findByCustomerId(customer.getId());

        if (!info.isFailed() && !info.brand().isEmpty() && !info.model().isEmpty()) {
            for (Vehicle v : customerVehicles) {
                if (info.brand().equalsIgnoreCase(v.getBrand())
                        && info.model().equalsIgnoreCase(v.getModel())) {
                    log.debug("车辆查找 — 品牌型号匹配: {} {}", info.brand(), info.model());
                    return v;
                }
            }
        }

        if (customerVehicles.size() == 1) {
            Vehicle v = customerVehicles.get(0);
            log.debug("车辆查找 — 客户名下唯一车辆: {} {}", v.getBrand(), v.getModel());
            return v;
        }

        log.warn("车辆查找失败 — userId: {}, brand: {}, model: {}, plate: {}, 名下车辆数: {}",
                customer.getUserId(), info.brand(), info.model(), info.licensePlate(), customerVehicles.size());
        return Vehicle.lookupFailed();
    }
}
